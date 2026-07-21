# Task 28 — Timer / Message / Signal event'lar (engine implementatsiyasi)

> **Ish papkasi:** `bpms/bpms-new-backend/`. Eski bpms READ-ONLY.
> **Holat:** v3 sxema jadvallari **tayyor** (`event_subscription`, `timer_definition`, `message_correlation`,
> `job.type=TIMER/MESSAGE`). **Engine tomoni yozilmagan** — token bu event'larda kutib, tashqi hodisa bilan
> davom etishi kerak.

---

## 0. Korpus grounding (nima REAL kerak)
Real sxemalarni (`compat-corpus`) tekshirdik:
- **Timer:** `intermediateCatchEvent` + `timerEventDefinition` + **`timeDuration`** (PT1M ×73, PT30S ×14) —
  **hamma** sxemada (4888/6004/7000 ham). Ishlatilishi: **polling loop** — `serviceTask "Iteration"`
  (`camunda:expression="$iteration + 1"`) → timer PT1M kutadi → gateway natijani qayta tekshiradi → aylanadi
  (masalan KATM/skoring async javobini kutish). **Busiz bu sxemalar oxirigacha bormaydi.**
- **Message / Signal / Boundary / timeDate / timeCycle:** korpusda **yo'q**.

**Prioritet:**
- **P0 — Intermediate timer catch (`timeDuration`)** — real, majburiy, korpus testi bilan.
- **P1 — timeDate / timeCycle, timer start event** — to'liqlik (korpusda yo'q, sintetik test).
- **P1 — Message / Signal** — mexanizm (sxema tayyor), sintetik BPMN bilan test; korpusda yo'q.
- **P2 — Boundary event (timer/message)** — dizayn beriladi, korpus ishlatmaydi (ixtiyoriy).

---

## 1. Umumiy tushunchalar (BPMN event o'lchamlari)
- **Pozitsiya:** start / intermediate (catch|throw) / boundary.
- **Catch vs Throw:** catch — kutadi; throw — yuboradi (signal/message chiqaradi).
- **Boundary interrupting vs non-interrupting:** interrupting — activity'ni uzadi; non-interrupting — activity
  davom etadi, yon token chiqadi.
- **Message vs Signal:** message — **1 ta** aniq instance'ga (correlation); signal — **hamma** obunachiga (broadcast).

---

## 2. P0 — Intermediate Timer Catch (`timeDuration`)
### 2.1 Parser (parser-camunda)
`intermediateCatchEvent` ичидаги `timerEventDefinition`ни node modelга chiqarish:
```
IntermediateCatchEventNode {
  id, name, incoming, outgoing,
  timer: TimerDefinition { kind = DURATION|DATE|CYCLE, expression = "PT1M" }
}
```
> Tekshiring: parser hozir bu elementni **skip** qilyaptimi (unknown node) yoki qisman o'qiyaptimi. `timeDate`/
> `timeCycle` ham o'qilsin (kind aniqlansin), lekin hisoblash §2.3.

### 2.2 Token event'ga kelganda (WAIT)
```java
if (node instanceof IntermediateCatchEventNode ev && ev.timer() != null) {
    Instant runAt = timerService.resolveRunAt(ev.timer(), clock.now(), evalVars); // §2.3
    // 1) obuna (tarix/lookup uchun)
    eventSubscriptions.save(new EventSubscription(
        id, tenantId, instanceId, token.id(), EventType.TIMER, /*event_name*/ null,
        /*activity_id*/ ev.id(), json({"runAt":runAt}), clock.now()));
    // 2) TIMER job (poller shuni o'qiydi)
    jobs.save(new JobRecord(id2, tenantId, instanceId, token.id(),
        "TIMER", json({"activityId":ev.id(),"subscriptionId":id}), JobStatus.PENDING, 0, /*run_at*/ runAt));
    jobQueue.enqueue(...);                 // rabbit rejimida; poller ham backstop
    token = token.waiting("WAITING");      // execution_token.status=WAITING
    tokenState.complete(stateId, COMPLETED); // event'ga "kirish" step'i yopiladi
    return;                                 // run() shu token uchun to'xtaydi
}
```

### 2.3 `TimerService.resolveRunAt` (ISO 8601)
| kind | format | hisoblash |
|---|---|---|
| DURATION | `PT1M`, `PT30S`, `P1D` | `now + Duration.parse(expr)` |
| DATE | `2026-07-20T10:00:00Z` | `OffsetDateTime.parse(expr).toInstant()` |
| CYCLE | `R3/PT10M` yoki cron `0 0 9 * * ?` | keyingi fire vaqti; `remaining_repeats`, `repeat_cycle` (§2.5) |

> **Clock:** engine'da mavjud `clock` (plan 21) ishlatilsin — testda deterministik (mock/advance) qilinadi.

### 2.4 Timer fire (Poller/Consumer → davom)
`TimerJobHandler implements TypedJobHandler { type()="TIMER" }`:
```java
@Transactional void handle(JobRecord job) {
    InstanceRecord inst = instances.findInstanceById(job.instanceId()).orElseThrow();
    if (inst.status()==TERMINATED || inst.status()==SUSPENDED) { jobs.markCanceled(job.id()); return; } // plan 27
    var p = json.readValue(job.payload(), MAP);
    TokenRecord token = tokens.findTokenById(job.tokenId()).orElseThrow();
    eventSubscriptions.deleteById((String)p.get("subscriptionId"));  // intermediate = single-fire
    ProcessDefinition model = registry.get(inst.definitionId());
    engine.getObject().resumeFrom(model, token, (String)p.get("activityId")); // token event'dan oldinga
    jobs.save(completed(job));
}
```
Poller (mavjud `@Scheduled`) `SELECT ... WHERE status='PENDING' AND run_at <= now()` — TIMER job ham shu
orqali olinadi (rabbit ishlamasa ham backstop).

### 2.5 Cycle (takroriy)
`R3/PT10M` → har fire'da: token oldinga suriladi (yoki bo'ndary bo'lsa yon token), `remaining_repeats--`, agar
qolgan bo'lsa **yangi** TIMER job (`run_at = now + PT10M`). `R/PT..` (cheksiz) → doim qayta rejalashtiradi.
> ⚠️ **Guardrail (plan 27 bilan bog'liq):** intermediate timer **polling loop**da ishlaydi (Iteration→timer→
> gateway→loop). Agar chiqish sharti hech qachon true bo'lmasa — 1 daqiqalik pauza bilan **cheksiz** aylanadi
> (bu CPU-spin emas, timer bilan yield qiladi — step-budjet tutmaydi). Shuning uchun **max-iteration cap**
> (node qayta-tashrif soni `execution_token_state`dan, yoki instance-age limit) → incident + SUSPEND. Aks holda
> "osilib qolgan" instance abadiy poll qiladi.

---

## 3. P1 — Timer start event
Deployment darajasida: `is_latest` definition'da timer start bo'lsa, engine bitta TIMER obuna/`job` (yoki
`timer_definition`) yaratadi; fire bo'lganda **yangi instance** boshlanadi (`PROCESS_START` job). Cycle bo'lsa
har davrda yangi instance. (Korpusda yo'q — sintetik test.)

---

## 4. P1 — Message events
### 4.1 Intermediate message catch
Token `messageEventDefinition`li catch'ga kelganda → `event_subscription(MESSAGE, event_name=<message name>,
activity_id, instance_id, token_id)`; token WAITING. TIMER'dan farqi — **job yo'q**, tashqi correlate kutadi.

### 4.2 Correlation API
`POST /api/v1/messages` `{ "name": "...", "businessKey": "...", "variables": {...} }`:
```sql
SELECT es.*, pi.id instance_id, es.token_id
  FROM event_subscription es
  JOIN process_instance pi ON pi.id = es.instance_id
 WHERE es.type='MESSAGE' AND es.event_name = :name
   AND pi.business_key = :businessKey AND pi.status='RUNNING'
 FOR UPDATE SKIP LOCKED;
```
Topilsa: `variables`ni `token_variable`ga qo'y → `message_correlation` yozuvi → obunani o'chir →
`engine.resumeFrom(token, activityId)`. Topilmasa: message-start bo'lsa yangi instance (§4.3), aks holda
`404/soft-drop` (log).

### 4.3 Message start event
`name`ga mos running correlation yo'q + definition'da message start bor → **yangi instance** (message = trigger).

### 4.4 Boundary message / message throw (P2)
Boundary — §6. Throw — `intermediateThrowEvent`/`sendTask` message chiqaradi (ichki: shu API'ni chaqirish yoki
tashqi connector).

---

## 5. P1 — Signal events (broadcast)
`POST /api/v1/signals` `{ "name": "...", "variables": {...} }`:
```sql
SELECT es.* FROM event_subscription es
  JOIN process_instance pi ON pi.id=es.instance_id
 WHERE es.type='SIGNAL' AND es.event_name=:name AND pi.status='RUNNING'
 FOR UPDATE SKIP LOCKED;   -- correlation YO'Q — HAMMA mos obuna
```
Har topilgan obuna uchun token davom etadi (broadcast — message'dan farqi shu). Signal start → har mos definition
uchun yangi instance. (Korpusda yo'q — sintetik test.)

---

## 6. P2 — Boundary event (timer/message) — dizayn (ixtiyoriy)
Activity'ga ulangan. Token activity'ga kirganda boundary obuna(lar) yaratiladi:
- **Interrupting:** boundary fire → activity token CANCELED (`execution_token_state` CANCELED), boundary flow bo'yicha davom.
- **Non-interrupting:** boundary fire → **yon token** boundary flow'ga; activity davom etadi (cycle timer → takror).
- Activity boundary'dan oldin tugasa → boundary obuna/`job` **o'chiriladi** (bekorga fire bo'lmasin).
> Korpus ishlatmaydi — keyingi bosqichga qoldirilsa bo'ladi; lekin polling'ni boundary timer bilan yozish
> (intermediate loop o'rniga) kelajakda tozaroq.

---

## 7. Obuna hayot sikli (lifecycle)
- **Yaratish:** token catch event'ga kirganda.
- **O'chirish:** fire bo'lganda (intermediate single-fire), yoki activity/boundary bekor bo'lganda.
- **Instance TERMINATE (plan 27):** `DELETE FROM event_subscription WHERE instance_id=:id` + TIMER joblar CANCEL —
  allaqachon plan 27 §2'da. (Moslashtirilsin.)

## 8. Konfiguratsiya
- Poller interval (masalan har 5–10s) — `run_at` aniqligi shunga bog'liq.
- Cron/timeCycle uchun timezone — konfiguratsiya (default UTC yoki tenant TZ).
- `in-process` rejimda ham TIMER job poller orqali ishlashi kerak (nafaqat rabbit).

## 9. Test (majburiy)
- **P0 real (4888/6004/7000):** polling loop — Iteration→timer(PT1M/PT30S)→gateway→loop → natija kelганда chiqadi,
  oxirigacha COMPLETED. **Test tez bo'lishi uchun** `clock`ni mock qilib timer'ni "o'tgan" qiling yoki `run_at`ni
  o'tmishga qo'ying (real 1 daqiqa kutilmasin).
- **P0 birlik:** intermediate timer catch → `execution_token.status=WAITING` + `job(type=TIMER, run_at≈now+PT30S)`
  + `event_subscription(TIMER)`. Poller fire → token oldinga, obuna o'chdi.
- **timeDate / timeCycle:** DATE → aniq vaqt; `R3/PT10M` → 3 marta fire, keyin to'xtaydi.
- **Message (sintetik bpmn):** intermediate message catch → `POST /messages` (name+businessKey) → correlate →
  token davom + `message_correlation` yozuvi. Mos kelmasa → soft-drop.
- **Signal (sintetik):** 2 instance obuna → `POST /signals` → **ikkalasi** ham davom (broadcast).
- **Guardrail:** chiqmaydigan polling loop → max-iteration cap → incident + SUSPEND (plan 27 bilan).
- Regres: timer'siz normal sxema o'zgarishsiz COMPLETED.

## 10. DoD
- [ ] Parser `intermediateCatchEvent`+`timerEventDefinition` (DURATION/DATE/CYCLE)ni node modelga chiqaradi.
- [ ] `TimerService.resolveRunAt` — ISO 8601 duration/date/cycle → `Instant`.
- [ ] Token timer catch'da: `event_subscription(TIMER)` + `job(TIMER, run_at)` + `WAITING`.
- [ ] `TimerJobHandler` (TypedJobHandler "TIMER") — poller fire → token davom; TERMINATED/SUSPENDED → ack-drop (plan 27).
- [ ] Cycle takror (`remaining_repeats`, yangi job); **max-iteration guardrail** (cheksiz poll oldini oladi).
- [ ] Message: intermediate catch + `POST /messages` correlation (`business_key`+name) + `message_correlation`; message start.
- [ ] Signal: `POST /signals` broadcast (hamma mos obuna).
- [ ] P0 real korpus testi (4888/6004/7000 polling) yashil — clock mock bilan tez.
- [ ] Eski bpms 0 diff.

## 11. Claude Code (terminal) topshiriq
```
Ish papkasi: bpms/bpms-new-backend/. Eski bpms faqat o'qish uchun.

docs/plans/28-timer-message-signal-events.md ni bajar. PRIORITET P0: intermediate timer catch (timeDuration,
masalan PT1M/PT30S) — real korpus (4888/6004/7000) polling loop shunga tayanadi.

1) parser-camunda: intermediateCatchEvent + timerEventDefinition (timeDuration/Date/Cycle) -> node modelga.
2) TimerService.resolveRunAt (ISO 8601 -> Instant, clock bilan).
3) Token timer'ga kelganda: event_subscription(TIMER) + job(TIMER, run_at) + token WAITING.
4) TimerJobHandler (TypedJobHandler "TIMER"): poller run_at<=now da fire -> engine.resumeFrom(token, activityId);
   TERMINATED/SUSPENDED instance -> ack-drop (plan 27).
5) Cycle takror + max-iteration guardrail (chiqmaydigan pollingни incident+SUSPEND).
Keyin P1: message (POST /messages, correlation business_key+name, message_correlation) va signal (POST /signals,
broadcast) — sintetik bpmn bilan test (korpusда yo'q).

Test tez bo'lishi uchun clock'ни mock qil (real 1 daqiqa kutilmasin). Avval parser va ExecutionEngine.run(),
Job poller/handlerlarни o'qib, rejani menga qisqa ayt, keyin yoz.
```
