# 02 — DATA MODEL

> **Haqiqий manba (source of truth):** `bpms-persistence-jpa/src/main/resources/db/changelog/` — Liquibase
> **XML** (`db.changelog-master.xml` + `changes/001..004.xml`); Postgres-spetsifik DDL `<sql>` CDATA ичида.
> Dizayn/ERD: `docs/bpms-schema.dbml`. Feature bog'lиqлиги: `docs/FEATURES.md`. Jadval indeksi + FK/ON DELETE
> to'lиq izohи: `docs/README.md` (mavjуd).
> **Holat:** v3 sxема **implement qилинган** (changelog toza bazада o'тади, trigger + seed sinалган).
> Jadval soni ~**29** (asосий 25 + `process_deployment_resource`, `group_entity`, `message_correlation`,
> `timer_definition`).

## Asosiy fikrlash modeli
- `execution_token` = **hozir qayerda** (UPSERT — har token bitta qator, faqat oxirgi pozitsiya)
- `execution_token_state` = **qanday yo'l bosdi** (har node-tashrif; node'ga kirganда `ACTIVE` INSERT, chiqишда
  **shu qator** `COMPLETED/FAILED` UPDATE — Camunda `ACT_HI_ACTINST` uslubi)
- `execution_log` = **nega shunday bo'ldi** (connector start/end/error, gateway qарори, instance start/end)
- `token_variable` = **qanday ma'lumot bilan** (EAV; eski 10 typed jadval o'rniga bitta)
- `job` = **keyingi qadam navbatда** (async, RabbitMQ)

## Jadvallar (25) — guruh bo'yicha, bir qatorли vazifa
### Katalog / definition
- `tenant` — multi-tenancy ildizi; har jadvalда `tenant_id` izolyatsiya.
- `process_deployment` — deploy hodisasi (bir nechta definitionни birlashtiradi).
- `process_deployment_resource` — deployment ичидаги xom fayl baytlари (BPMN/DMN/FORM), Camunda `ACT_GE_BYTEARRAY` uslуби.
- `process_definition` — deploy qilingan BPMN (`bpmn_xml` canonical, `process_key`, `version`, `is_latest`, `status`).
- `connector_definition` — serviceTask `connectorId` → Java bean konfiguratsiyasи (HTTP/GRPC/KAFKA/INTERNAL).

### Instance / token
- `process_instance` — bitta ishga tushirish (`status`, `business_key`, `parent/root_instance_id`, `terminated_by`, `cancel_reason`, soft-delete).
- `process_instance_migration` — ishlаётган instance'ни yangi definition versiyasiga ko'chirish (`mapping`: eski node→yangi node).
- `execution_token` — token joriy holati (UPSERT); multi-instance sanoqlар (`mi_total/completed/active`); `incident_id`.
- `execution_token_state` — har node-tashrif (step) trace; `sequence_no`, `entered/exited_at`, `duration_ms`, `status`.

### O'zgaruvchilар
- `token_variable` — EAV (`scope`=INSTANCE/TOKEN/GLOBAL, `type`, `value_text`/`value_json`, `revision`).
- `token_variable_history` — har o'zgаришда eski qiymat (trigger orqali); **partitsiyали** (`changed_at`).

### User task
- `user_task` — userTask'да to'xtaган protsess (`assignee`, `candidate_groups/users`, `delegated_to`, `escalated_at`, `due_date`, `form_key`, `submitted_data`).

### Async / job
- `job` — ish navbati (`type`=SERVICE_TASK/TIMER/MESSAGE/ASYNC_JOB/PROCESS_START; `idempotency_key`, `lock_expiry_at`, `max_attempts`, `run_at`, `repeat_cycle`).
- `dead_letter_job` — max retry tugаган joblар (manual qayta ishga tushirish uchun).

### Event / external
- `event_subscription` — TIMER/MESSAGE/SIGNAL/CONDITIONAL obunаsi (Camunda `act_ru_event_subscr` analogi).
- `external_task` — lock→fetch→complete pattern (topic, worker_id, lock_expiry_at).
- `message_correlation` — message hodisasi qaysi instance/subscription'ga correlate qilingani tarixi.
- `timer_definition` — BPMN timer ta'rifi (DATE/DURATION/CYCLE) metadata — `job`dan alohida.

### Diagnostika / xatolik
- `incident` — xatolik (FAILED_JOB/ERROR_BOUNDARY/CONNECTOR_ERROR; OPEN→RESOLVED).
- `execution_listener_log` — BEFORE/AFTER execute listener chaqiruvlар (`token_state_id` bilan bog'lanган).
- `execution_log` — ijro tafsiloti; **partitsiyали** (`created_at`).

### Boshqaruv
- `group_entity` — foydalanuvchi guruhlari katalogi (`user_task.candidate_groups` + authorization uchun).
- `process_authorization` — kim nima qила oladi (principal × resource × permission).
- `process_comment` — instance/task izohlар.
- `process_stats_daily` — kunlik aggregatsiya (throughput, avg duration, SLA breach).

### Tizim
- `databasechangelog` / `databasechangeloglock` — Liquibase avtomatik (qo'lда yozilmайди).

## Muhim dizayn qарорлар (v3 DDL — reja 25)
- **FK ON DELETE:** runtime-bola (token/variable/log/job/task) → **CASCADE**; katalog (tenant/definition) →
  **RESTRICT**; diagnostik havolalar (incident) → **SET NULL**. To'liq jadval: reja 25 §1.
- **CHECK:** har enum ustunга `CHECK (col IN (...))`; lekin **`node_type` va `event_type` CHECKsiz** (ochiq ro'yxat).
- **Circular FK:** `execution_token ↔ incident` — ustun avval FKsiz, keyin `ALTER ADD` (reja 25 §3).
- **Partitioning:** `execution_log`, `token_variable_history` — `PARTITION BY RANGE`, PK partition kalitini o'z
  ichига oladi (`PRIMARY KEY (id, created_at/changed_at)`), oylik partitsiya + `DEFAULT`.
- **Trigger:** `token_variable` UPDATE → eski qiymat `token_variable_history`ga, `revision++` (reja 25 §5).

## Migratsiya guruhlari (Liquibase XML, 4 changeset) — IMPLEMENT QILINGAN
Root `db.changelog-master.xml` → `changes/*.xml`:
- `001-core.xml` — tenant, process_deployment, process_deployment_resource, process_definition,
  connector_definition, process_instance, process_instance_migration.
- `002-execution.xml` — execution_token, execution_token_state, execution_listener_log,
  execution_log (partitsiyали). *(incident_id ustunlari FKsiz — §circular.)*
- `003-jobs-events.xml` — job, dead_letter_job, event_subscription, external_task, user_task,
  token_variable, token_variable_history (partitsiyали) + `trg_token_variable_history` trigger.
- `004-auth-stats.xml` — incident (+ circular `incident_id` FK'ni `ALTER ADD` bilan yopadi), group_entity,
  process_authorization, process_comment, process_stats_daily, message_correlation, timer_definition + seed.

## ⚠️ Ma'lum cheklov
`JpaPersistenceAdapter` hozir `tenant_id`/`deployment_id`ни seed tenant `t-demo`дан oladi — **request-scoped
multi-tenancy hali yo'q** (header/JWT tenant konteksti — alohida ish).
