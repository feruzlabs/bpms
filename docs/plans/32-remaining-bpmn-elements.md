# Task 32 — Qolgan BPMN element'lari (7 tur) — engine ijrosi

> **Ish papkasi:** `bpms/bpms-new-backend/`. Eski bpms READ-ONLY.
> **Holat:** engine hozir 9 node turini ijro qiladi. Bu reja qolgan 7 turni qo'shadi (28-timer alohida).
> **⚠️ MUHIM tartib:** har yangi turni `ExecutionEngine`ga `if (node instanceof X)` bilan qo'shish uni
> **793 → 1200+ qatorли god-class** qiladi. Shuning uchun **avval reja 33 (NodeBehavior refactor)** — keyin bu
> element'lар **handler** sifatida qo'shilsin. (Refactor bo'lмаса ham qo'shsa bo'ladi, lekin qarz o'sadi.)

> **Korpus grounding:** joriy sxемаларда bu 7 turdan **hech biri yo'q** — ular **to'liqлik/kelajак** uchun
> (faqat 28-timer real). Shuning uchun ustuvorлik pastроq, lekin platformа to'liq BPMS bo'lиши uchun kerak.

---

## Faza A — Event'lar

### A1. `boundaryEvent` (timer / error / message)
Activity chetига ulanган; activity ishлаётганда fire bo'lса:
- **interrupting:** activity token CANCELED, boundary flow'ga o'tади.
- **non-interrupting:** yon token boundary flow'ga; activity davom.
Turlari: **timer** (SLA/timeout — reja 28 timer infra qayta ishlатилади), **error** (activity ички xatosини
ushлайди), **message/signal** (event_subscription).
- Engine: activity'ga kirганда boundary obuna/`job`(timer) yaratилади; activity tugаса — o'chирилади (bekorга fire yo'q).
- Schema: `event_subscription` (bor). Error boundary — connector xatosини `incident` o'rнига boundary flow'ga.
- **Prereq:** reja 28 (timer) — boundary timer o'shани ishlатади.

### A2. `intermediateThrowEvent` (message / signal / none)
Token o'rtада hodisа **chиqаради**, odатда **darhol davom**:
- **message throw** — mos `event_subscription`(MESSAGE)ни correlate qилиб o'shа token'ни uyg'отади (yoki tashqи).
- **signal throw** — barcha mos `event_subscription`(SIGNAL) (broadcast).
- **none** — belgi (log nuqtаsi) — pass-through.
- Schema: `event_subscription`, `message_correlation` (bor). Reja 28'даги message/signal correlate mantig'и qayta ishlатiladi.

---

## Faza B — Struktura (reuse / dekompozitsiya)

### B1. `callActivity` — boshqа protsess ta'рифини chaqириш
Ota token bola **instance** yaratади (`parent_instance_id`/`root_instance_id`), **kutади**; bola COMPLETED bo'лса davom.
- Engine: `PROCESS_START` job (reja 21) bilan bola instance; ota token `WAITING`; bola tugаganда callback (bola end →
  ota token resume). Input/output mapping (ota↔bola variable).
- Schema: `process_instance.parent_instance_id/root_instance_id` (bor).
- **⭐ Reja 27 bog'liqlиги:** bu element `RunawayGuard.checkSpawnDepthBeforeStart`ни **ochади** — recursive call
  chuqurлиги cap'и shu yerда ulanadi (hozir dormant).

### B2. `subProcess` — embedded quyi-oqim
Shu protsess ичидаги guruhланган quyи-oqim (alohида ta'рif emas). Turlari:
- **embedded** — ichки scope (o'z start/end); token ичкарига kirадi, ичкаридаги end → tashqарига chиqади.
- **multi-instance** (forEach) — N marta (parallel/ketма-кет); `execution_token.mi_total/mi_completed/mi_active` (bor).
- **event subprocess** (ixtiyoriy, keyingi) — ичкаридаги event bilan trigger.
- Engine: subprocess scope token boshqаruvи; multi-instance sanoq (mi_* jadval ustunлари).

---

## Faza C — Message task'lар

### C1. `receiveTask` — xabар kutувчи task
`intermediate message catch`ning task ko'ринiшi: token WAITING, `event_subscription`(MESSAGE); correlate kelганда davom.
Reja 28 message mantig'и qayta ishlатилади (task sifatiда).

### C2. `sendTask` — xabар yuborувчи task
`message throw`ning task ko'ринiшi: xabар yuboradи, davom. Ko'pincha serviceTask+connector bilan bir xil — engine
uni A2 (message throw) yoki connector orqали bajarsин.

---

## Faza D — Qaror

### D1. `businessRuleTask` — DMN decision table
Qaror'ни **DMN** jadvалига topширади; natижа o'zгарувчисига. *Misol:* "yosh+daromad+reyting → limit". Kredit skoring
uchun gateway+script o'rнига deklaратив jadval.
- **Katta:** DMN parser + evaluator (yoki tashqи DMN kutubxona: Camunda DMN engine embed). Alohida modul (`bpms-dmn`).
- Schema: DMN ta'рифи `process_deployment_resource` (FORM/DMN bytes — bor).
- Korpус DMN ishlатмаган — **eng past ustuvorlik** (kelajак).

---

## Prioritet (korpus + qiymат bo'yicha)
1. **B1 callActivity + B2 subProcess** — reuse/dekompozitsiya, 27 spawn-guard'ни ochади (arxitектура qiymати yuqori).
2. **A1 boundaryEvent (timer/error)** — SLA/xato boshqаruvи (best-practice; 28 timer prereq).
3. **A2 intermediateThrow + C1/C2 receive/sendTask** — tashqи integratsiya (message/signal infra 28 bilan).
4. **D1 businessRuleTask (DMN)** — alohida modul, eng katta, korпусда yo'q — oxирида.

## DoD (umumий)
- [x] Har element `NodeBehavior` handler sifatида (reja 33 refactoridan keyin), yangi `if instanceof` god-class'ga qo'шilmaydi.
- [x] boundaryEvent (interrupting/non-interrupting, timer/error) — activity uzилиши/yon-token; obuna lifecycle.
- [x] callActivity — bola instance, parent zanjiri, spawn-depth guard ulanган (reja 27 dormant → aktив).
- [x] subProcess (embedded + multi-instance mi_* sanoq).
- [x] intermediateThrow + receive/sendTask (message/signal, reja 28 infra qayta ishlатилади).
- [x] businessRuleTask (DMN) — deferred: connector path + DMN_DEFERRED log; `bpms-dmn` later.
- [x] Har biriga test (sintetik bpmn, korпусда yo'q); eski bpms 0 diff.

## Cursor topshirig'i — quyida (alohida). Tavsiya: avval reja 33 (refactor), keyin bu Faza A→D bosqichли.
