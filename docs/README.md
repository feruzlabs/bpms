# bpms-new-backend — v3 schema (25 tables)

Source of truth: `bpms-persistence-jpa/src/main/resources/db/changelog/` (Liquibase **XML**).
Design doc: `bpms-schema.dbml` (this folder) — paste into https://dbdiagram.io for the ERD.
Business features (A–L): [`FEATURES.md`](./FEATURES.md) — feature → jadval → engine komponent.

Migration tool: **Liquibase XML** — root `db.changelog-master.xml`, ichida 4 guruh (`changes/*.xml`).
PostgreSQL-spetsifik DDL (PARTITION, plpgsql trigger, CHECK, array) `<sql>` CDATA ichida.

| Changeset | Tables |
|---|---|
| `changes/001-core.xml` | tenant, process_deployment, process_deployment_resource, process_definition, connector_definition, process_instance, process_instance_migration |
| `changes/002-execution.xml` | execution_token, execution_token_state, execution_listener_log, execution_log (partitioned) |
| `changes/003-jobs-events.xml` | job, dead_letter_job, event_subscription, external_task, user_task, token_variable, token_variable_history (partitioned) + `trg_token_variable_history` |
| `changes/004-auth-stats.xml` | incident (+ closes the circular `incident_id` FK on execution_token/execution_token_state), group_entity, process_authorization, process_comment, process_stats_daily, message_correlation, timer_definition + seed |

## Table index (one line each)

| Table | Vazifa |
|---|---|
| `tenant` | Bank/mijoz izolyatsiyasi — barcha runtime jadvallar shu orqali ajratiladi (multi-tenancy). |
| `process_deployment` | Bitta "joylashtirish" hodisasi — bir nechta BPMN/DMN resursini birlashtiradi. |
| `process_deployment_resource` | Deployment ichidagi xom fayl baytlari (BPMN/DMN/FORM), Camunda `ACT_GE_BYTEARRAY` uslubi. |
| `process_definition` | Versiyalangan BPMN ta'rifi (`process_key`+`version`), `is_latest` — joriy versiya belgisi. |
| `connector_definition` | ServiceTask'ning `connectorId` → Java `@Component` bean konfiguratsiyasi (HTTP/GRPC/KAFKA/INTERNAL). |
| `process_instance` | Bitta ishga tushirilgan protsess — `business_key` (masalan `request_id`) orqali topiladi. |
| `process_instance_migration` | Ishlab turgan instance'ni eski versiyadan yangisiga ko'chirish tarixi (node mapping bilan). |
| `execution_token` | "Hozir qayerda" — protsess grafida joriy pozitsiya (bitta yoki bir nechta parallel token). |
| `execution_token_state` | "Qanday yo'l bosdi" — har node tashrifi uchun ACTIVE→COMPLETED/FAILED yozuvi (insert-then-update). |
| `execution_listener_log` | Har BEFORE/AFTER execute listener chaqiruvi — alohida debug birligi. |
| `execution_log` | Connector/gateway/instance darajasidagi diagnostika (nega bunday natija chiqdi). Partitsiyali. |
| `job` | Asinxron ish birligi (serviceTask/timer/message) — poller/consumer shu jadvalni ishlaydi. |
| `dead_letter_job` | `max_attempts`dan oshgan job'lar — qo'lda tekshirish/qayta ishga tushirish uchun. |
| `event_subscription` | Timer/message/signal kutayotgan token'lar — tashqi hodisa bilan correlate qilinadi. |
| `external_task` | Tashqi worker pattern (lock→fetch→complete) — og'ir/uzoq ishlarni asosiy engine'dan chiqarish. |
| `user_task` | Inson bajaradigan qadam — assignment, candidate group, delegation, escalation, forma ma'lumotlari. |
| `token_variable` | Protsess o'zgaruvchilari (EAV), scope: INSTANCE/TOKEN/GLOBAL, `revision` bilan. |
| `token_variable_history` | `token_variable`ning UPDATE tarixi — trigger orqali avtomatik yoziladi. Partitsiyali. |
| `incident` | Xatolik holati (failed job / error boundary / connector error) — token/job bilan bog'langan. |
| `group_entity` | Foydalanuvchi guruhlari katalogi — `user_task.candidate_groups` va authorization uchun. |
| `process_authorization` | Kim (USER/GROUP/ROLE) qaysi resursda qaysi amalni (READ/START/...) bajara oladi. |
| `process_comment` | Instance yoki task'ga qoldirilgan izohlar (audit/kommunikatsiya). |
| `process_stats_daily` | Kunlik agregatsiya — throughput, o'rtacha davomiylik, SLA breach hisobotlari uchun. |
| `message_correlation` | Message hodisasi qaysi instance/subscription'ga correlate qilinganining tarixi. |
| `timer_definition` | BPMN'dagi timer ta'rifi (DATE/DURATION/CYCLE) — `job`dan alohida, faqat metadata. |

## Bog'liqlik jadvali (FK → ON DELETE)

To'liq ro'yxat va sabab — plan 25 §1. Tez-tez keladigan savol: nega ba'zi FK `RESTRICT`, ba'zilari `CASCADE`?
- **Runtime bola yozuvlar** (token, variable, log, job, task) → o'z instance/definition bilan **CASCADE**.
- **Katalog/ajdod yozuvlar** (tenant, definition, deployment) → tasodifiy yo'qotishdan **RESTRICT**.
- **Diagnostik/audit havolalar** (incident_id, job_id kabi) → **SET NULL** (asosiy oqim buzilmasin).

## Circular FK: execution_token ↔ incident

`execution_token.incident_id` va `execution_token_state.incident_id` ustunlari `002-execution.xml`da FKsiz
yaratiladi (incident jadvali hali yo'q). `004-auth-stats.xml` incident'ni yaratgandan so'ng
`ALTER TABLE ... ADD CONSTRAINT ... FOREIGN KEY (incident_id) REFERENCES incident(id) ON DELETE SET NULL`
bilan yopiladi. Sabab: incident job'ga bog'liq (`incident.job_id → job`), job esa 003'da yaratiladi — shuning
uchun ikkala tomon bir vaqtda yaratilolmaydi.

## Partitsiyalash

`execution_log` va `token_variable_history` — `PARTITION BY RANGE` (2026-07, 2026-08, 2026-09 + `_default`).
PK ikkalasida ham `(id, created_at)` / `(id, changed_at)` — partition kaliti PK ichida bo'lishi shart (Postgres
talabi). Hech qaysi boshqa jadval bu ikkitasiga FK bilan ishora qilmaydi, shuning uchun partitsiyalash
muammosiz. Kelajakda oylik partitsiyani qo'shish — qo'lda yangi `create table ... partition of ...` yoki
`pg_partman`/`@Scheduled`.

## Trigger: `trg_token_variable_history`

`token_variable` UPDATE bo'lganda (`value_text`/`value_json` haqiqatan o'zgargan bo'lsa) eski qiymat
`token_variable_history`ga yoziladi, `revision` avtomatik oshadi. Tekshirilgan: `620 → 655` UPDATE →
history'da 1 qator (`old_value_text='620'`, `revision=0`), `token_variable.revision` 0→1.

## Seed

Toza bazada `t-demo` tenant + `dep-credit-1` deployment + `pd-credit-1` (`TUNE_CREDIT_REQUEST` v1, placeholder
BPMN) avtomatik qo'shiladi (`004-auth-stats.xml`, changeset `bpms:004-seed-demo-tenant`).

## ⚠️ Ma'lum cheklov (keyingi bosqich uchun)

`JpaPersistenceAdapter` v3 `tenant_id`/`deployment_id` ni hozir **seed tenant `t-demo`** + definition'dan
nusxa olish orqali to'ldiradi (request-scoped multi-tenancy hali yo'q). To'liq tenant konteksti (header/JWT)
alohida ish.
