# 06 — LUG'AT (Glossary)

## Domen / BPMN
- **Process definition** — deploy qilingan BPMN ta'rifi (shablon). `process_key` + `version`.
- **Process instance** — ta'rifning bitta ishga tushirilishi (real ariza). `business_key` bilan tanилади.
- **Business key** — instance'ни biznes bo'yicha aniqlовчи kalit (masalan `request_id`). Message correlation'да ishlатилади.
- **Token** — ijro "kursori": instance ичида hozir qайси node'да turганини ko'рсатади. Gateway'да bo'lиниб/birlаshиб ketishi mumkin.
- **Node / activity** — BPMN elementi (task, gateway, event).
- **Sequence flow** — node'lар orasидаги yo'l (strelka); `condition` bo'lиши mumkin.
- **Gateway** — yo'l ayرиши: exclusive (1 yo'l), inclusive (bir necha), parallel (split/join).
- **Default flow** — gateway'да hech shart mos kelмаса olinadigan yo'l. **Shartсиз** flow = implicit default (reja 22).
- **Service task** — avtomatик qadam (connector/API chaqiruvi).
- **User task** — inson bajарадиган qadam (operator tasdiqлайди).
- **Call activity / subprocess** — boshqa process'ни chaqiриш; bola instance (`parent_instance_id`).
- **Boundary event** — node chetига ulanган hodisa (timer/error/message) — node'ни uzиб yo'l ochади.
- **Multi-instance** — bir node'ни N marta (parallel/ketма-кет) bajариш (`mi_total/completed/active`).

## Engine / runtime
- **DefinitionRegistry** — parse qилинган modellар xotира cache'и (runtime re-parse yo'q).
- **ExecutionEngine / run()** — token'ни node'дан node'ga suradigan yadро.
- **Wait-state** — token to'xtаб tashqi hodisa kutаётган holат (userTask, serviceTask job, timer, message).
- **Job** — bajарилиши kerak bo'lган async ish (`SERVICE_TASK`, `PROCESS_START`, `TIMER`, ...).
- **JobDispatcher** — job'ни `type` bo'yича to'g'ри handler'ga yo'наltirади.
- **Poller** — `@Scheduled`, `PENDING` + `run_at <= now()` job'larни tanлайди.
- **Consumer** — RabbitMQ `@RabbitListener`, job'ни bajаради.
- **Idempotency key** — at-least-once yetказишда takror bajаришни bloklovчи kalit.
- **Lock expiry** — worker crash bo'lса job/task avtomат ozod bo'lиши vaqti.
- **Dead letter** — max retry tugаган job (manual qayta ishга tushириш uchun).
- **Incident** — engine xatoлиги yozуви (job/connector fail); OPEN → RESOLVED.
- **Execution listener** — node BEFORE/AFTER execute'да ishга tushадиган kod/expression (`execution_listener_log`).
- **EAV** — Entity-Attribute-Value: o'zгарувчилар bitta jadvalда (`token_variable`), typed emas ustunда.
- **Scope** — o'zгарувчи ko'rиnиши: INSTANCE (butun) / TOKEN (shu shox) / GLOBAL.
- **Revision** — o'zгарувчининг versiyа raqami; har UPDATE'да trigger tarихга yozади.
- **Terminate** — instance'ни **mutlaq** to'xtаtiш (token CANCELED, qaйтмас). **Suspend** — qайтариладиган pauza.
- **Step budget** (`MAX_STEPS_PER_RUN`) — bitta `run()`да maksimal transition; sinxron cheksиз loop tutgичи.
- **Cooperative cancellation** — engine har qadamда "to'xтатилдими?" deб tekshириши (tashqи stop shунда ishлайди).

## Infra / build
- **Hexagonal (Ports & Adapters)** — engine markazда, tashqи dunyo port interfeyslар orqали.
- **SPI** — connector/kengaytма plaginlар kontrakти (creditConveyer v6–v9).
- **Virtual Threads (VT)** — Java 21 arzon threadlар, bloklovчи I/O uchun.
- **Liquibase** — DB migratsiya vositasi (loyihада **XML** changelog, DDL `<sql>` CDATA ичида).
- **Compat-corpus** — parity test sxемалари: `TUNE_CREDIT_REQUEST_4888/6004/7000`.
- **Parity** — yangи engine eski engine bilan **bir xил** natижа berиши.

## Domen (kredit)
- **Credit conveyor / skoring** — ariza bo'yича qарор pipeline'и (skoring API → gateway → tasdiqлаш).
- **creditConveyer v6/v7/v8/v9** — connectorlар versiyalари (SPI orqали import qилинган).
