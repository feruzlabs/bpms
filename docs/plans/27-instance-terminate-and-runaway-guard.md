# Task 27 — Instance'ni majburiy to'xtatish (TERMINATE) + runaway/recursive loop guardrail

> **Ish papkasi:** `bpms/bpms-new-backend/`. Eski bpms READ-ONLY.
> **Muammo:** ishlab turgan instance recursive/cheksiz loop'ga tushdi (token to'xtovsiz aylanyapti yoki
> o'zini-o'zi qayta ishga tushiryapti). Uni **qo'lda mutlaq to'xtatish** kerak.

---

## 0. Asosiy tamoyil (nega oddiy "DELETE"/"status=STOP" ishlamaydi)
Instance ijrosi **async job**'lar orqali (RabbitMQ consumer) va/yoki engine `run()` ichidagi **sinxron token
harakati** orqali boradi. Ikkita alohida holat, ikkita alohida yechim kerak:

1. **Async boundary orasida** (job navbatda kutyapti / keyingi job hali olinmagan) → DB'da `TERMINATED` qilib,
   consumer job'ni **olishдан oldin** tekshirsa — bas. Job "ack-and-drop" bo'ladi.
2. **Sinxron loop ichida** (`run()` bitta chaqiruvda token'ni A→B→A→B… cheksiz suryapti, async boundary'ga
   umuman chiqmayapti) → tashqi "stop" **hech qachon ko'rilmaydi**, chunki thread DB'ni qayta o'qimaydi. Buni
   faqat **engine ichidagi checkpoint** to'xtata oladi: (a) har transition'da bekor-flagni tekshirish, va/yoki
   (b) **step-budjet** (bitta `run()`da maksimal transition soni — oshsa majburiy uziladi).

> Ya'ni "to'xtatish tugmasi" **kooperativ** bo'lishi shart: engine muntazam ravishda "meni to'xtatishдими?" deб
> so'rab turishi kerak. Buni qo'shmasak, hech qanday API cheksiz sinxron loop'ni to'xtata olmaydi.

---

## 1. Ikki rejim: SUSPEND (qaytariladigan) vs TERMINATE (mutlaq)
| | SUSPEND | TERMINATE |
|---|---|---|
| Maqsad | vaqtincha pauza (tekshirish uchun) | butunlay o'ldirish |
| Token | qoladi (WAITING/holati saqlanadi) | hammasi **CANCELED** |
| Job | ushlab turiladi (olinmaydi) | **CANCELED** (navbatдан chiqadi) |
| Qaytarish | `resume` bilan davom etadi | **yo'q** (qaytmas) |
| Status | `SUSPENDED` | `TERMINATED` |

Runaway loop uchun odatда: avval **SUSPEND** (to'xtatib, sababini ko'rish) → keyin **TERMINATE** yoki tuzatib
`resume`. Lekin darhol o'ldirish kerak bo'lsa — to'g'ridan-to'g'ri TERMINATE.

---

## 2. TERMINATE algoritmi (bitta tranzaksiyada, idempotent)
`POST /api/v1/process-instances/{id}/terminate?reason=...`
```sql
-- 0) instance'ni qulflab olish (parallel terminate/step bilan poyga bo'lmasin)
SELECT status FROM process_instance WHERE id=:id FOR UPDATE;
-- allaqachon TERMINATED/COMPLETED bo'lsa -> no-op (idempotent), qaytar

-- 1) instance
UPDATE process_instance
   SET status='TERMINATED', terminated_by=:user, cancel_reason=:reason, ended_at=now()
 WHERE id=:id AND status IN ('RUNNING','SUSPENDED','FAILED');

-- 2) barcha faol token
UPDATE execution_token SET status='CANCELED', updated_at=now()
 WHERE instance_id=:id AND status IN ('ACTIVE','WAITING','WAITING_JOB');

-- 3) ochiq step'lar (token_state) yopiladi
UPDATE execution_token_state SET status='CANCELED', exited_at=now()
 WHERE instance_id=:id AND status='ACTIVE';

-- 4) navbatдаги job'lar — MUHIM: keyingi qadam ijro etilmasin
UPDATE job SET status='FAILED', updated_at=now()   -- yoki 'CANCELED' status qo'shsangiz
 WHERE instance_id=:id AND status IN ('PENDING','RETRY','LOCKED');

-- 5) timer/message/signal obunalari — kelajakda uyg'otmasin
DELETE FROM event_subscription WHERE instance_id=:id;

-- 6) external task'lar
UPDATE external_task SET status='FAILED', error_message='instance terminated'
 WHERE instance_id=:id AND status IN ('CREATED','LOCKED');

-- 7) ochiq user task'lar
UPDATE user_task SET completed=true, completed_at=now(), deleted_at=now()
 WHERE token_id IN (SELECT id FROM execution_token WHERE instance_id=:id) AND completed=false;

-- 8) ochiq incident'lar
UPDATE incident SET status='RESOLVED', resolved_at=now(), resolved_by=:user
 WHERE instance_id=:id AND status='OPEN';

-- 9) audit
INSERT INTO execution_log(id, tenant_id, instance_id, event_type, details, created_at)
VALUES (gen_random_uuid()::text, :tenant, :id, 'INSTANCE_TERMINATED',
        jsonb_build_object('by',:user,'reason',:reason), now());
```
> **Tartib muhim:** avval `process_instance.status='TERMINATED'` (2-9 qadamlардан oldin ham bo'lishi mumkin) —
> shunда parallel ishlab turgan consumer keyingi qadamда statusни ko'rib to'xtaydi. 4-qadam (job cancel)
> navbatдаги ishни o'ldiradi.

---

## 3. Kooperativ checkpoint — engine va consumer buni **hurmat qilishi** shart
DB'ни yangilash o'zi kifoya emas; ijro yo'llarига tekshiruv qo'shiladi:

**(a) Consumer job olganda — birinchi qator:**
```java
void handle(JobRecord job) {
    InstanceRecord inst = instances.findInstanceById(job.instanceId()).orElseThrow();
    if (inst.status() == TERMINATED || inst.status() == SUSPENDED) {
        // ack-and-drop: bajaRMA, keyingi qadamни enqueue QILMA
        jobs.markCanceled(job.id());
        return;
    }
    ... // odatдаги ijro
}
```

**(b) Engine `run()` — har token transition'дан OLDIN:**
```java
while (token.isActive()) {
    if (terminationGuard.isTerminated(instanceId)) {      // <-- kooperativ tekshiruv
        cancelToken(token); return;
    }
    if (++steps > MAX_STEPS_PER_RUN) {                    // <-- step-budjet (sinxron loop tutgichi)
        raiseIncident(instanceId, "STEP_BUDGET_EXCEEDED"); suspend(instanceId); return;
    }
    advance(token);
}
```
- `terminationGuard.isTerminated(id)` — arzon bo'lishi uchun: qisqa TTL (masalan 1s) cache yoki har N-qadamда
  bir marta DB `SELECT status` (har qadamда DB o'qish qimmat). Distributed bo'lsa — RabbitMQ **fanout**
  ("terminate:instanceX") broadcast bilan barcha node cache'ini invalidatsiya qilish tavsiya etiladi.
- `MAX_STEPS_PER_RUN` — konfiguratsiya (masalan 10_000). Bu **har qanday** sinxron cheksiz loop'ни kafolat
  bilan uzadi (hatto terminate bosilmasa ham).

---

## 4. Runaway/recursive loop'ни OLDINI OLISH (proaktiv guardrail)
Manual terminate — reaktiv. Loop umumian yuzaga kelmasligи uchun avtomatik chegaralar:

| Guardrail | Qanday | Ta'sir |
|---|---|---|
| **Step-budjet** (`MAX_STEPS_PER_RUN`) | bitta `run()`да transition sanog'i | oshsa → incident + SUSPEND |
| **Node qayta-tashrif chegarasi** | `execution_token_state`да bir xil `(instance_id,node_id)` sanog'i | > N (masalan 1000) → incident `LOOP_DETECTED` + SUSPEND |
| **Subprocess/call-activity chuqurligi** | `process_instance.parent_instance_id` zanjiri uzunligi | > N (masalan 50) → start rad etiladi (recursive spawn to'xtaydi) |
| **Instance spawn cap** | bitta `root_instance_id` ostidа yaratilган instance soni | > N → PROCESS_START job rad etiladi + incident |
| **Umumiy token/step cap** | instance bo'yicha `execution_token_state` qatorlari | > N → majburiy FAIL |

Node qayta-tashrif detektori (loop'ни aniqlaydigan asosiy so'rov):
```sql
SELECT node_id, count(*) c
  FROM execution_token_state
 WHERE instance_id=:id
 GROUP BY node_id
HAVING count(*) > :threshold;   -- masalan 1000 -> shubhали loop
```
> Recursive **subprocess** holati (instance o'zini call-activity orqali qayta chaqiryapti) — `parent_instance_id`
> chuqurligи bilan tutiladi. Recursive **token loop** (bir instance ichида) — node qayta-tashrif + step-budjet
> bilan.

---

## 5. Butun daraxtни o'ldirish (recursive spawn natijasi)
Recursion ko'p **bola instance** yaratган bo'lsa (call activity), rootни terminate qilganda barcha avlodни ham:
```sql
WITH RECURSIVE tree AS (
    SELECT id FROM process_instance WHERE id=:rootId
    UNION ALL
    SELECT c.id FROM process_instance c JOIN tree t ON c.parent_instance_id = t.id
)
SELECT id FROM tree;   -- shu id'lar ustида §2 terminate qadamlarини qo'llash
```
API: `POST /process-instances/{id}/terminate?cascade=true` → root + barcha avlod TERMINATED.

---

## 6. API endpoint'lar
- `POST /api/v1/process-instances/{id}/suspend` → SUSPENDED (qaytариladigan).
- `POST /api/v1/process-instances/{id}/resume` → RUNNING (SUSPENDED'дан; pauza qilingan job'lар qayta enqueue).
- `POST /api/v1/process-instances/{id}/terminate?reason=...&cascade=true|false` → TERMINATED (mutlaq).
- (ixtiyoriy) `POST /api/v1/admin/jobs/{id}/cancel` — bitta osilib qolgan job'ни alohida bekor qilish.
- Ruxsat: `process_authorization` — `permission='UPDATE'` yoki alohida `TERMINATE` (plan 25 enum'iga qo'shish
  mumkin) bo'lганlar terminate qila oladi.

---

## 7. Test (majburiy)
- **Manual terminate (async):** RUNNING instance + PENDING job → terminate → job olinmaydi (ack-drop),
  instance TERMINATED, token CANCELED, event_subscription 0.
- **Sinxron cheksiz loop:** ataylab A→B→A loop bpmn → `MAX_STEPS_PER_RUN` oshadi → incident + SUSPEND
  (jarayon osilib qolmaydi, CPU cheksiz aylanmaydi).
- **Node qayta-tashrif:** threshold oshguncha aylanadigan sxema → `LOOP_DETECTED` incident.
- **Recursive subprocess:** o'zini chaqiruvchi call-activity → depth cap → start rad etiladi.
- **Cascade:** root + 3 bola instance → `terminate?cascade=true` → hammasi TERMINATED.
- **Idempotent:** ikki marta terminate → ikkinchisi no-op (xato yo'q).
- Regres: normal instance oxirигача COMPLETED bo'ladi (guardrail'lар xalaqit bermaydi).

---

## 8. DoD
- [ ] TERMINATE (§2) — tranzaksion, idempotent, token/job/event/task/incident hammasi yopiladi + audit log.
- [ ] SUSPEND/RESUME (qaytариladigan) endpoint'lar.
- [ ] Consumer TERMINATED/SUSPENDED instance job'ини ack-drop qiladi (§3a).
- [ ] Engine `run()`да kooperativ tekshiruv + `MAX_STEPS_PER_RUN` step-budjet (§3b) — sinxron loop kafolat bilan uziladi.
- [ ] Runaway guardrail'lар (§4): node qayta-tashrif + subprocess depth + spawn cap → incident + SUSPEND.
- [ ] Cascade terminate (§5) — root + avlodlar.
- [ ] Barcha test (§7) yashil; eski bpms 0 diff.

## 9. Claude Code (terminal) topshiriq
```
Ish papkasi: bpms/bpms-new-backend/. Eski bpms faqat o'qish uchun.

/tmp/27-instance-terminate-and-runaway-guard.md ni bajar. Asosiy talab: ishlab turgan instance'ni QO'LDA
mutlaq to'xtatish (TERMINATE) + recursive/cheksiz loop'ni oldini oluvchi guardrail.

Muhim: (1) TERMINATE §2 tranzaksion (instance/token/job/event_subscription/user_task/incident + audit);
(2) consumer TERMINATED instance job'ini ack-drop qilsin (§3a); (3) engine run() da kooperativ termination
tekshiruvi + MAX_STEPS_PER_RUN step-budjet (§3b) — bu sinxron cheksiz loop'ni kafolat bilan uzadi;
(4) node qayta-tashrif / subprocess depth / spawn cap guardrail'lari incident+SUSPEND bilan (§4);
(5) cascade terminate (§5). SUSPEND/RESUME ham qo'sh.

Avval ExecutionEngine.run(), ProcessEngineService, consumer (JobDispatcher/handlerlar) va instance status
bilan bog'liq fayllarni o'qib, rejani menga qisqa ayt, keyin yoz. Testlar bilan tasdiqla (ayniqsa sinxron
loop step-budjet bilan uzilishini).
```
