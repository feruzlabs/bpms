# Task 26 — `FEATURES.md`: v3 sxema qaysi biznes-feature'larni yoqadi (A–L)

> **Maqsad:** `bpms-schema.dbml` v3'dagi har bir jadval guruhi qaysi **real biznes-feature**ni beradi, uni
> **qachon** ishlatish, va **engine oqimida qayerda** ishga tushishini hujjatlash. Bu — jadval ta'rifi emas,
> **feature → jadval → engine komponent** bog'lovchi hujjat.
> **Deliverable:** bitta `FEATURES.md` (`bpms-new-backend/docs/` ichida).

---

## 0. Hujjat strukturasi (majburiy)
1. **"Engine arxitekturasi"** bo'limi (boshida) — asosiy komponentlar va ular ishlatadigan jadvallar:
   - **Token Engine** — `execution_token`, `execution_token_state` (token'ni node'dan node'ga suradi).
   - **Job Poller** (`@Scheduled`) — `job` (`status='PENDING' AND run_at <= now()` tanlaydi).
   - **Job Executor / Consumer** (RabbitMQ) — `job`, `dead_letter_job`.
   - **Event Handler** — `event_subscription` (timer/message/signal correlate qiladi).
   - **External Task API** — `external_task` (lock→fetch→complete).
   - **Listener Runner** — `execution_listener_log` (BEFORE/AFTER execute).
   - **Incident Manager** — `incident`.
   - **Stats Aggregator** (`@Scheduled` kunlik) — `process_stats_daily`.
   - **Auth Guard** — `process_authorization`.
2. **A–L feature'lar** — har biri alohida sarlavha ostida, quyidagi **5 bo'lim** bilan:
   1. **Feature nomi** — biznes tilida.
   2. **Qaysi jadvallar yoqadi** — nechta jadval birga.
   3. **Qanday ishlaydi** — INSERT/UPDATE/SELECT oqimi qadam-baqadam.
   4. **Real misol** — kredit-skoring / bank conveyor ssenariysi.
   5. **Engine kodida qayerda** — qaysi komponent (poller/executor/listener/handler).
   + **Sekvens diagramma** (matn: `Client → Engine → DB`) + kamida **1 ta SQL query namunasi**.
3. **Oxirida** — **"Feature × Jadval" matritsa** jadvali.

> **Domen:** barcha misollar kredit-skoring bo'lsin: *hujjat yuklandi → skoring API chaqirildi → gateway qaror
> qildi → tasdiqlashga user task → to'lov jadvali yaratildi.* Instance `business_key = request_id` (4888/6004/7000
> real sxemalaridagi kabi).

---

## 1. Feature'lar ro'yxati va asosiy urg'u (Claude Code'ga yo'riqnoma)

### A. Timer & rejalashtirilgan vazifalar
Jadvallar: `job` (`due_date`, `repeat_cycle`, `remaining_repeats`) + `event_subscription` (`type=TIMER`).
Yoritish: boundary timer / intermediate timer / takroriy (ISO-8601 `R3/PT10M`) timer. Poller `run_at <= now()`
bo'yicha tanlaydi; takroriy timer bajarilgach `remaining_repeats--` va yangi `job` (yangi `run_at`).
Misol: "skoring 10 daqiqada javob bermasa — eslatma, 3 marta; keyin operatorga eskalatsiya."
SQL: poller tanlovi —
```sql
SELECT * FROM job
WHERE tenant_id = :t AND status='PENDING' AND run_at <= now()
ORDER BY run_at
FOR UPDATE SKIP LOCKED LIMIT 100;
```

### B. Message & Signal orqali jarayonlar aloqasi
Jadvallar: `event_subscription` (`type=MESSAGE|SIGNAL`).
Yoritish: tashqi tizim `business_key + event_name` bo'yicha correlate qiladi (MESSAGE = 1 instance; SIGNAL =
hamma mos instance uyg'onadi). Correlate topilgach token WAITING→ACTIVE, obuna o'chiriladi.
Misol: "skoring servisi tayyor bo'lgach `SCORING_READY` message'ini `request_id`ga yuboradi."
SQL: message correlation —
```sql
SELECT es.* FROM event_subscription es
JOIN process_instance pi ON pi.id = es.instance_id
WHERE es.type='MESSAGE' AND es.event_name=:name AND pi.business_key=:bk;
```

### C. External Task pattern (mikroservis worker'lar)
Jadval: `external_task`.
Yoritish: worker `topic` bo'yicha `lock` (worker_id + lock_expiry_at) → fetch → complete. Worker crash bo'lsa
`lock_expiry_at < now()` — boshqa worker qayta oladi. `status`: CREATED→LOCKED→COMPLETED/FAILED.
Misol: "og'ir PDF-parsing tashqi worker'da — `pdf-extract` topic."
SQL: lock (fetch&lock) —
```sql
UPDATE external_task SET status='LOCKED', worker_id=:w, lock_expiry_at=now()+interval '5 min'
WHERE id IN (SELECT id FROM external_task
             WHERE topic=:topic AND (status='CREATED' OR (status='LOCKED' AND lock_expiry_at<now()))
             ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT :n)
RETURNING *;
```

### D. Ishonchli job bajarilishi (reliability)
Jadvallar: `job` (`idempotency_key`, `lock_expiry_at`, `max_attempts`, `attempts`) + `dead_letter_job`.
Yoritish: at-least-once (RabbitMQ) → `idempotency_key` UNIQUE takror bajarishni bloklaydi; xato → `attempts++`,
`status=RETRY`, backoff bilan `run_at`; `attempts >= max_attempts` → `dead_letter_job`ga ko'chirish; keyin manual
re-enqueue. `lock_expiry_at` — worker crash'da ozod bo'lish.
Misol: "skoring API 3 marta 500 qaytardi → dead letter → admin qayta ishga tushiradi."
SQL: retry / DLQ ko'chirish namunasi (UPDATE + INSERT…SELECT).

### E. Multi-instance (forEach / parallel)
Jadval: `execution_token` (`mi_total`, `mi_completed`, `mi_active`).
Yoritish: bir node ustida N marta (parallel yoki ketma-ket); har bola tugaganda `mi_completed++`;
`mi_completed == mi_total` bo'lganda MI yopiladi, token oldinga.
Misol: "3 kafil har biriga alohida skoring — hammasi tugagach umumiy qaror."
SQL: yakunlanish tekshiruvi (`UPDATE ... SET mi_completed = mi_completed+1 ... RETURNING mi_completed, mi_total`).

### F. Variable versiyalash & audit
Jadvallar: `token_variable` (`revision`, `scope`) + `token_variable_history` + trigger (plan 25 §5).
Yoritish: har UPDATE'da trigger eski qiymatni tarixga yozadi, `revision++`. "3-qadamda bu o'zgaruvchi nima edi"
→ history'dan `changed_at`/`revision` bo'yicha. `scope`: INSTANCE (butun instance) / TOKEN (shu shox) / GLOBAL.
Misol: "`credit_score` skoringdan keyin 620, qayta hisobdan keyin 655 — ikkala qiymat tarixi ko'rinadi."
SQL: "N-revizyada qiymat" —
```sql
SELECT old_value_text FROM token_variable_history
WHERE instance_id=:i AND name='credit_score' AND revision=:r;
```

### G. Incident management (xatolik boshqaruvi)
Jadvallar: `incident` + `execution_token.incident_id` / `execution_token_state.incident_id`.
Yoritish: job/connector xato → `incident` (OPEN) ochiladi, `execution_token.incident_id` to'ldiriladi, token
to'xtaydi (FAILED/WAITING). Admin resolve qilgach → job qayta enqueue, token davom etadi, incident RESOLVED.
Misol: "skoring API endpoint noto'g'ri → CONNECTOR_ERROR incident → admin config tuzatib resolve."
SQL: ochiq incidentlar dashboard so'rovi.

### H. Jarayon versiyasini yangilash (migration)
Jadvallar: `process_instance_migration` + `process_definition` (`version`, `is_latest`).
Yoritish: ishlab turgan instance'ni yangi BPMN versiyasiga ko'chirish; `mapping` (eski node→yangi node) bo'yicha
`execution_token.current_node_id` va `execution_token_state` yangilanadi; `instance.definition_id` yangi versiyaga.
Misol: "v1'da 620 chegara edi, v2'da 650 — ishlayotgan arizalarni v2'ga ko'chirish."

### I. User task — assignment, delegation, escalation
Jadval: `user_task` (`assignee`, `candidate_groups`, `candidate_users`, `delegated_to`, `escalated_at`,
`due_date`, `priority`, `form_key`, `submitted_data`, `claim_time`).
Yoritish: task candidate_group'ga tushadi → foydalanuvchi claim (assignee+claim_time) → delegate → `due_date`
o'tsa timer-job `escalated_at` belgilaydi → complete'da `submitted_data` yoziladi, token davom etadi.
Misol: "arizani `credit-officers` guruhi ko'radi; 2 soatda claim bo'lmasa boshliqqa eskalatsiya."
SQL: "mening navbatim" —
```sql
SELECT * FROM user_task
WHERE tenant_id=:t AND completed=false
  AND (assignee=:u OR candidate_users @> ARRAY[:u] OR candidate_groups && :myGroups)
ORDER BY priority DESC, due_date;
```

### J. Ruxsatlar (authorization)
Jadval: `process_authorization`.
Yoritish: `principal (USER/GROUP/ROLE) × resource_type × resource_id × permission`. `resource_id='*'` = shu
turdagi barcha resurs. START/CLAIM/READ… tekshiruvi har API'da.
Misol: "`credit-officers` roli `TUNE_CREDIT_REQUEST`ni START qila oladi; `auditors` faqat READ."
SQL: ruxsat tekshiruvi (`WHERE principal_id IN (:user,:groups) AND permission='START' AND resource_id IN (:def,'*')`).

### K. Monitoring & statistika
Jadvallar: `process_stats_daily` + `execution_log` + `execution_listener_log`.
Yoritish: kunlik aggregator `process_instance`/`execution_token_state`'dan throughput, o'rtacha davomiylik, SLA
breach hisoblab `process_stats_daily`ga yozadi. "Nega bu instance shu natijani berdi" → `execution_log`
(connector/gateway) + `execution_listener_log` (listener) + `execution_token_state` (yo'l).
Misol: "bugun 1240 ariza, 87% avto-tasdiq, o'rtacha 4.2s; 1 ta SLA breach."
SQL: kunlik aggregatsiya (`INSERT INTO process_stats_daily SELECT ... GROUP BY definition_id, date`).

### L. Multi-tenancy
Jadval: `tenant` + barcha jadvaldagi `tenant_id`.
Yoritish: bitta engine ko'p mijozga; har so'rovda `tenant_id` filtri (izolyatsiya); unique'lar tenant bo'yicha
(`(tenant_id, business_key)`, `(tenant_id, process_key, version)`). Row-level tenant scoping.
Misol: "Bank A va Bank B bir engine'da; A operatori B arizasini ko'rmaydi."
SQL: har so'rovda `WHERE tenant_id = :currentTenant`.

---

## 2. "Feature × Jadval" matritsa (oxirida — ✅ bilan to'ldiring)
Ustunlar = asosiy jadvallar; qatorlar = A–L. Namuna sarlavha:

| Feature | job | event_subscription | external_task | dead_letter_job | execution_token | token_variable(+history) | incident | process_instance_migration | user_task | process_authorization | process_stats_daily | execution_log | tenant |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| A Timer | ✅ | ✅ | | | | | | | | | | | ✅ |
| B Message/Signal | | ✅ | | | ✅ | | | | | | | | ✅ |
| … | | | | | | | | | | | | | |
> Claude Code qolganini to'ldiradi (C–L).

---

## 3. Talablar (checklist)
- [ ] Boshida "Engine arxitekturasi" (komponent → jadval).
- [ ] A–L har biri 5 bo'lim + sekvens diagramma (matn) + ≥1 SQL query.
- [ ] Barcha real misollar kredit-skoring domenida, `business_key=request_id`.
- [ ] Oxirida to'ldirilgan "Feature × Jadval" matritsa.
- [ ] Bitta `FEATURES.md` (docs/ ichida).

## 4. Claude Code (terminal) topshiriq
```
Ish papkasi: bpms/bpms-new-backend/. /tmp/26-features-documentation.md va bpms-schema.dbml ni asos qilib
docs/FEATURES.md yoz. Struktura: "Engine arxitekturasi" bo'limi + A–L feature (har biri 5 bo'lim: nom, jadvallar,
qanday ishlaydi, real kredit-skoring misol, engine komponent) + matn sekvens diagramma + har feature'da ≥1 SQL
namuna + oxirida to'liq Feature×Jadval matritsa. Barcha misol kredit-skoring domenida (business_key=request_id).
Faqat hujjat — kod o'zgartirilmaydi.
```
