# Task 25 — Schema v3: to'liq PostgreSQL DDL + migratsiya + trigger + seed

> **Ish papkasi:** `bpms/bpms-new-backend/`. Eski bpms (`bpms/liveScoring-bpms-backend/`) READ-ONLY.
> **Kirish:** `bpms-schema.dbml` v3 (25 jadval — Camunda/Zeebe uslubi). Bu sxema plan 23/24'dagi 9-jadvalli
> versiyani **kengaytiradi** (ustiga tenant, incident, external_task, event_subscription, multi-instance,
> migration, authorization, stats qo'shadi). Plan 23'ning `execution_token_state` va `execution_listener_log`
> allaqachon shu v3 ichida.

---

## 0. Migratsiya vositasi — QAROR (avval hal qiling)
⚠️ **Ziddiyat:** plan 24 Flyway→**Liquibase** ko'chishni belgilagan; bu topshiriq matni esa `V1__core.sql`
(Flyway naming) deydi. **Standart tanlov:** plan 24 (eng oxirgi aniq qaror) — **Liquibase, SQL-formatted
changelog**, lekin **fayllarni aynan shu 4 mantiqiy guruhga** bo'lamiz (core / execution / jobs_events /
auth_stats). Agar baribir toza Flyway kerak bo'lsa — bir xil guruhlash `V1__..V4__` nomi bilan ishlaydi;
farqi faqat header (`--liquibase formatted sql` vs Flyway'da header yo'q) va tracking jadval.

Quyida hammasi **SQL** — ikkala vositaga ham mos (faqat changeset header qo'shilsa Liquibase bo'ladi).

---

## 1. FK `ON DELETE` qarorlari (har biri — sabab bilan)
> **Tamoyil:** *Runtime bola* yozuvlar (token, variable, log, job, task…) → o'z instance/definition bilan
> **CASCADE**. *Katalog/ajdod* yozuvlar (tenant, definition, deployment) → tasodifiy yo'qotishdan **RESTRICT**.
> *Diagnostik/audit* havolalar → o'chirish asosiy oqimni buzmasin uchun **SET NULL**.

| FK | ON DELETE | Sabab (1 qator) |
|---|---|---|
| `process_deployment.tenant_id → tenant` | **RESTRICT** | Tenant o'chirilishi ataylab bo'lishi kerak, deploymentlar bilan birga yo'qolmasin |
| `process_definition.tenant_id → tenant` | **RESTRICT** | Katalog yozuvi — tenant tasodifan o'chsa, ta'riflar yo'qolmasin |
| `process_definition.deployment_id → process_deployment` | **RESTRICT** | Deployment ta'riflarni birlashtiradi; ta'rif borligida deployment o'chmasin |
| `connector_definition.definition_id → process_definition` | **CASCADE** | Connector config ta'rifga tegishli — ta'rif o'chsa, konfiguratsiyasi ham |
| `process_instance.tenant_id → tenant` | **RESTRICT** | — |
| `process_instance.definition_id → process_definition` | **RESTRICT** | Instance'lari bor ta'rifni o'chirib bo'lmaydi (tarix buziladi) |
| `process_instance.deployment_id → process_deployment` | **RESTRICT** | — |
| `process_instance.parent_instance_id → process_instance` | **CASCADE** | Subprocess/call-activity bolasi ota-instance bilan o'ladi |
| `process_instance.root_instance_id → process_instance` | **SET NULL** | Faqat navigatsiya havolasi; self-ref cascade tsiklini oldini olish |
| `process_instance_migration.instance_id → process_instance` | **CASCADE** | Migratsiya yozuvi instance'ga bog'liq |
| `process_instance_migration.*_definition_id → process_definition` | **RESTRICT** | Ta'rif tarixi saqlanadi |
| `execution_token.instance_id → process_instance` | **CASCADE** | Token instance'ga tegishli |
| `execution_token.parent_token_id → execution_token` | **CASCADE** | Bola token (gateway split) ota bilan |
| `execution_token.incident_id → incident` | **SET NULL** | Incident diagnostik — o'chirilsa token yashashda davom etadi (⚠️ circular — §3) |
| `execution_token_state.token_id → execution_token` | **CASCADE** | Step tarixi token bilan |
| `execution_token_state.instance_id → process_instance` | **CASCADE** | — |
| `execution_token_state.incident_id → incident` | **SET NULL** | Diagnostik havola (⚠️ circular — §3) |
| `token_variable.instance_id → process_instance` | **CASCADE** | O'zgaruvchi instance bilan |
| `token_variable.token_id → execution_token` | **SET NULL** | Token-scope o'chsa ham instance-scope qiymat qolishi mumkin |
| `token_variable_history.variable_id → token_variable` | **CASCADE** | Audit joriy o'zgaruvchi bilan (retention siyosati bo'lsa RESTRICT'ga o'zgartiring) |
| `user_task.token_id → execution_token` | **CASCADE** | Task token bilan |
| `job.instance_id → process_instance` | **CASCADE** | Job instance bilan |
| `job.token_id → execution_token` | **SET NULL** | Job token'siz ham qolishi mumkin (PROCESS_START) |
| `dead_letter_job.instance_id → process_instance` | **CASCADE** | — |
| `event_subscription.instance_id → process_instance` | **CASCADE** | Obuna instance bilan |
| `external_task.instance_id → process_instance` | **CASCADE** | — |
| `incident.instance_id → process_instance` | **CASCADE** | Incident instance bilan |
| `incident.token_id → execution_token` | **SET NULL** | Token o'chsa incident tarixi qolsin |
| `incident.token_state_id → execution_token_state` | **SET NULL** | — |
| `incident.job_id → job` | **SET NULL** | Job qayta ishlansa incident havolasi uzilsin, lekin incident qolsin |
| `execution_listener_log.token_state_id → execution_token_state` | **CASCADE** | Listener log step bilan |
| `execution_log.instance_id → process_instance` | **CASCADE** | — |
| `process_authorization.tenant_id → tenant` | **RESTRICT** | — |
| `process_comment.instance_id → process_instance` | **CASCADE** | Izoh instance bilan |
| `process_comment.task_id → user_task` | **SET NULL** | Task o'chsa izoh instance'da qolsin |
| `process_stats_daily.definition_id → process_definition` | **CASCADE** | Statistika ta'rif bilan |

> Barcha `tenant_id → tenant` — **RESTRICT** (yagona tamoyil).

---

## 2. CHECK constraint'lar (enum ustunlar)
Har biriga `CHECK (col IN (...))`:
```
tenant.status                 IN ('ACTIVE','SUSPENDED')
process_deployment.source     IN ('API','UI','MAVEN')
process_definition.status     IN ('ACTIVE','SUSPENDED','DEPRECATED')
connector_definition.connector_type IN ('HTTP','GRPC','KAFKA','INTERNAL')
process_instance.status       IN ('RUNNING','SUSPENDED','COMPLETED','FAILED','TERMINATED','CANCELLED')
execution_token.status        IN ('ACTIVE','WAITING','WAITING_JOB','COMPLETED','FAILED','CANCELED')
execution_token_state.status  IN ('ACTIVE','COMPLETED','FAILED','CANCELED')
token_variable.scope          IN ('INSTANCE','TOKEN','GLOBAL')
token_variable.type           IN ('STRING','LONG','DOUBLE','BOOLEAN','DATE','JSON','OBJECT','FILE')
token_variable_history.type   IN (yuqoridagi bilan bir xil)
job.type                      IN ('SERVICE_TASK','TIMER','MESSAGE','ASYNC_JOB','PROCESS_START')
job.status                    IN ('PENDING','LOCKED','RUNNING','COMPLETED','FAILED','RETRY')
event_subscription.type       IN ('TIMER','MESSAGE','SIGNAL','CONDITIONAL')
external_task.status          IN ('CREATED','LOCKED','COMPLETED','FAILED')
incident.type                 IN ('FAILED_JOB','ERROR_BOUNDARY','CONNECTOR_ERROR')
incident.severity             IN ('INFO','WARNING','ERROR')
incident.status               IN ('OPEN','RESOLVED','DELETED')
execution_listener_log.phase  IN ('BEFORE','AFTER','START','END','TAKE')
execution_listener_log.listener_type IN ('CLASS','EXPRESSION','DELEGATE_EXPRESSION')
execution_listener_log.status IN ('SUCCESS','FAILED')
process_authorization.principal_type IN ('USER','GROUP','ROLE')
process_authorization.resource_type  IN ('PROCESS_DEFINITION','PROCESS_INSTANCE','USER_TASK')
process_authorization.permission     IN ('READ','START','UPDATE','DELETE','CLAIM')
```
> **CHECK QO'YMANG** (ochiq ro'yxat — kelajakda kengayadi, brittle migratsiyadan qochish):
> `execution_token_state.node_type` (BPMN element turlari ko'p) va `execution_log.event_type`. Bularni
> erkin `varchar` qoldiring, ilova darajasida validatsiya qiling.

---

## 3. ⚠️ Circular FK: `execution_token` ↔ `incident`
`execution_token.incident_id → incident`, lekin `incident.token_id/token_state_id → execution_token(_state)`.
Bir migratsiyada ikkalasi bir-biriga bog'liq. **Yechim (standart):**
1. `execution_token` va `execution_token_state`'ni **`incident_id` ustuni bilan, lekin FKsiz** yarating (V2).
2. `incident`'ni `job` yaratilgandan **keyin** yarating (`incident.job_id → job`) — ya'ni V3/V4'da.
3. So'ng `ALTER TABLE ... ADD CONSTRAINT ... FOREIGN KEY (incident_id) REFERENCES incident(id) ON DELETE SET NULL`
   — `execution_token` va `execution_token_state` uchun (V4 boshida).

---

## 4. Partitioning
`execution_log` va `token_variable_history` — **`PARTITION BY RANGE`**. **Muhim gotcha:** partitsiyalangan
jadvalda PK partition kalitini o'z ichiga olishi shart.
```sql
-- execution_log
CREATE TABLE execution_log (
    id          varchar(64)  NOT NULL,
    ...,
    created_at  timestamp    NOT NULL DEFAULT now(),
    PRIMARY KEY (id, created_at)                     -- partition kaliti PK ichida
) PARTITION BY RANGE (created_at);

CREATE TABLE execution_log_2026_07 PARTITION OF execution_log
    FOR VALUES FROM ('2026-07-01') TO ('2026-08-01');
CREATE TABLE execution_log_2026_08 PARTITION OF execution_log
    FOR VALUES FROM ('2026-08-01') TO ('2026-09-01');
CREATE TABLE execution_log_2026_09 PARTITION OF execution_log
    FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');
-- default partition (chegaradan tashqari yozuvlar yo'qolmasin)
CREATE TABLE execution_log_default PARTITION OF execution_log DEFAULT;
```
`token_variable_history` — xuddi shunday, `PARTITION BY RANGE (changed_at)`, `PRIMARY KEY (id, changed_at)`,
3 oylik partitsiya + default.
> Bu ikki jadvalga **hech qaysi FK ishora qilmaydi** (faqat ular boshqalarga ishora qiladi) — shuning uchun
> partitsiyalash muammosiz. Kelajakda oylik partitsiyani `pg_partman` yoki `@Scheduled` bilan avtomatlashtirish
> mumkin (hozircha qo'lda 3 oy).

---

## 5. Trigger — `token_variable` → `token_variable_history`
`token_variable` UPDATE bo'lganda eski qiymat avtomatik tarixга yoziladi + revision oshadi.
```sql
CREATE OR REPLACE FUNCTION fn_token_variable_history() RETURNS trigger AS $$
BEGIN
    -- faqat qiymat haqiqatan o'zgargan bo'lsa
    IF OLD.value_text IS DISTINCT FROM NEW.value_text
       OR OLD.value_json IS DISTINCT FROM NEW.value_json THEN
        INSERT INTO token_variable_history(
            id, variable_id, instance_id, name, type,
            old_value_text, old_value_json, revision, changed_by, changed_at)
        VALUES (
            gen_random_uuid()::text, OLD.id, OLD.instance_id, OLD.name, OLD.type,
            OLD.value_text, OLD.value_json, OLD.revision, NEW.updated_at::text, now());
        NEW.revision   := OLD.revision + 1;   -- revision avtomatik oshadi
        NEW.updated_at := now();
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_token_variable_history
    BEFORE UPDATE ON token_variable
    FOR EACH ROW EXECUTE FUNCTION fn_token_variable_history();
```
> `gen_random_uuid()` — `pgcrypto` yoki PG13+ core'da bor. Migratsiya boshida `CREATE EXTENSION IF NOT EXISTS
> pgcrypto;` qo'ying. `changed_by` — trigger DB darajasida foydalanuvchini bilmaydi; ilova `updated_at`/session
> orqali beradi yoki keyin `SET LOCAL app.user` bilan uzatiladi (hozircha soddalashtirilgan).

---

## 6. Migratsiya fayl bo'linishi (dependency tartibi bilan)
| Fayl | Jadvallar | Izoh |
|---|---|---|
| **V1__core** | `tenant`, `process_deployment`, `process_definition`, `connector_definition`, `process_instance`, `process_instance_migration` | Katalog + instance ildizi. `pgcrypto` ext shu yerda. |
| **V2__execution** | `execution_token` (incident FKsiz), `execution_token_state` (incident FKsiz), `execution_listener_log`, `execution_log` (partitsiyali) | Token oqimi. incident_id ustunlari bor, FK yo'q (§3). |
| **V3__jobs_events** | `job`, `dead_letter_job`, `event_subscription`, `external_task`, `user_task`, `token_variable`, `token_variable_history` (partitsiyali) + **trigger** (§5) | Async + task + variable. |
| **V4__auth_stats** | `incident` (endi `job` mavjud), **ALTER** execution_token/state ADD incident FK (§3), `process_authorization`, `process_comment`, `process_stats_daily` + **seed** (§7) | incident + qolgan FK'lar + seed. |

> Tartib majburiy: `incident` `job`'ga bog'liq → V4'da, `job`dan keyin. execution_token'ning incident FK'si ham V4'da.

---

## 7. Seed data (V4 oxirida)
```sql
INSERT INTO tenant(id, code, name, status)
VALUES ('t-demo', 'DEMO', 'Demo Bank', 'ACTIVE');

INSERT INTO process_definition(id, tenant_id, process_key, version, name, bpmn_xml, is_latest, status)
VALUES ('pd-credit-1', 't-demo', 'TUNE_CREDIT_REQUEST', 1, 'Credit scoring demo',
        '<?xml version="1.0"?><definitions>...placeholder...</definitions>', true, 'ACTIVE');
```

---

## 8. Qo'shimcha deliverable'lar
- **`README.md`** — 25 jadval, har biri **bir qator** vazifa (indeks jadval ko'rinishida).
- **ERD / bog'liqlik jadvali** — DBML'ni `bpms-schema.dbml`dan dbdiagram.io PNG qilib eksport qiling, YOKI
  "child → parent (FK, ON DELETE)" ustunli markdown jadval. (Toza ERD imkoni bo'lmasa — jadval yetarli.)

---

## 9. DoD
- [ ] 25 jadval to'liq DDL, har FK'da §1'dagi `ON DELETE`.
- [ ] Barcha enum ustunga CHECK (§2); `node_type`/`event_type` CHECKsiz (ochiq).
- [ ] Circular FK §3 bo'yicha (incident_id ustuni FKsiz → keyin ALTER ADD).
- [ ] `execution_log` + `token_variable_history` partitsiyali (PK partition kalitini o'z ichiga oladi) + 3 oy + default.
- [ ] Trigger (§5) — UPDATE'da tarix + revision oshadi; test bilan tasdiqlangan.
- [ ] 4 migratsiya fayli (Liquibase SQL-formatted, guruhlangan) — toza bazada `mvn spring-boot:run` xatosiz o'tadi.
- [ ] Seed (§7) qo'llaniladi; `databasechangelog` (yoki flyway history) to'g'ri.
- [ ] `README.md` + ERD/bog'liqlik jadvali.
- [ ] Eski bpms 0 diff.

---

## 10. Claude Code (terminal) topshiriq
```
Ish papkasi: bpms/bpms-new-backend/. Eski bpms (bpms/liveScoring-bpms-backend/) faqat o'qish uchun.

/tmp/25-schema-v3-ddl-migrations.md va bpms-schema.dbml ni asos qilib, v3 sxema (25 jadval) uchun to'liq
PostgreSQL DDL yoz. Migratsiya vositasi: Liquibase SQL-formatted changelog, 4 guruhga bo'lingan
(V1_core / V2_execution / V3_jobs_events / V4_auth_stats — planning §6).

Muhim: (1) FK ON DELETE §1 jadval bo'yicha; (2) enum CHECK'lar §2 (node_type/event_type CHECKsiz);
(3) circular incident FK §3 — ALTER ADD bilan; (4) execution_log + token_variable_history partitsiyali §4;
(5) trigger §5; (6) seed §7; (7) README + bog'liqlik jadvali.

Avval bpms-schema.dbml va hozirgi migratsiya fayllar holatini o'qib, rejani menga qisqa ayt, keyin yoz.
Toza bazada ishga tushirib (mvn spring-boot:run yoki tegishli), barcha jadval yaratilganini va trigger
ishlashini tasdiqla (bitta token_variable UPDATE → token_variable_history'da 1 qator).
```
