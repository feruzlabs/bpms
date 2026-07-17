# FEATURES — v3 sxema qaysi biznes-feature'larni yoqadi (A–L)

> **Maqsad:** `bpms-schema.dbml` v3'dagi jadval guruhlari qaysi **real biznes-feature**ni beradi, uni
> **qachon** ishlatish, va **engine oqimida qayerda** ishga tushishini bog'lash.
> Bu jadval ta'rifi emas — **feature → jadval → engine komponent** hujjati.
>
> **Domen:** barcha misollar kredit-skoring. Instance `business_key = request_id`
> (4888 / 6004 / 7000 real sxemalaridagi kabi).

Manba: `bpms-schema.dbml`, Liquibase `db/changelog/changes/001–004-*.xml`, engine SPI (`ExecutionEngine`, portlar).

---

## Engine arxitekturasi

Asosiy komponentlar va ular o‘qiydigan/yozadigan jadvallar:

| Komponent | Vazifa | Asosiy jadvallar |
|---|---|---|
| **Token Engine** (`ExecutionEngine`) | Token'ni node'dan node'ga suradi; BEFORE/AFTER listener; step lifecycle | `execution_token`, `execution_token_state`, `execution_listener_log`, `execution_log` |
| **Job Poller** (`@Scheduled`) | `status='PENDING' AND run_at <= now()` tanlaydi | `job` |
| **Job Executor / Consumer** (RabbitMQ yoki in-process) | Job bajaradi; xatoda retry / DLQ | `job`, `dead_letter_job` |
| **Event Handler** | Timer / message / signal correlate | `event_subscription`, `message_correlation` |
| **External Task API** | lock → fetch → complete | `external_task` |
| **Listener Runner** (engine ichida) | Node start/end listenerlar | `execution_listener_log` (via `ListenerLogPort`) |
| **Incident Manager** | Job/connector xatosini ochiq incident qiladi | `incident`, `execution_token.incident_id` |
| **Stats Aggregator** (`@Scheduled` kunlik) | Throughput / duration / SLA | `process_stats_daily`, `execution_log` |
| **Auth Guard** | API oldida ruxsat tekshiruvi | `process_authorization`, `tenant` |

Debug qatlamlari (plan 23):

- **"Hozir qayerda"** → `execution_token`
- **"Qanday yo'l bosdi"** → `execution_token_state` (`ACTIVE` → `COMPLETED`/`FAILED`, `sequence_no`)
- **"Listenerlar qanday ishladi"** → `execution_listener_log`
- **"Nega bunday natija"** → `execution_log` (connector/gateway)

---

## A. Timer & rejalashtirilgan vazifalar

### 1. Feature nomi
Boundary / intermediate / takroriy (ISO-8601) timerlar — skoring kechiksa eslatma, eskalatsiya.

### 2. Qaysi jadvallar yoqadi
`job` (`due_date`, `repeat_cycle`, `remaining_repeats`, `run_at`) + `event_subscription` (`type=TIMER`) + `timer_definition` (metadata) + `tenant`.

### 3. Qanday ishlaydi
1. Token timer node'ga keladi → `event_subscription` (`TIMER`) + `job` (`type=TIMER`, `run_at=due`).
2. Job Poller `run_at <= now()` bo‘yicha tanlaydi, status `LOCKED`/`RUNNING`.
3. Bajarilgach token `WAITING` → `ACTIVE`, obuna o‘chiriladi.
4. Takroriy (`R3/PT10M`): `remaining_repeats--`, yangi `job` yangi `run_at` bilan.

### 4. Real misol
`request_id=4888` — skoring API 10 daqiqada javob bermasa eslatma, 3 marta; keyin operatorga eskalatsiya (user task).

### 5. Engine kodida qayerda
**Job Poller** + **Event Handler**; timer meta — `timer_definition`.

**Sekvens**
```
Client/BPMN → Token Engine (timer catch) → INSERT event_subscription + job
Job Poller → SELECT job FOR UPDATE SKIP LOCKED → Executor
Executor → token ACTIVE, DELETE subscription → Token Engine davom etadi
```

**SQL**
```sql
SELECT * FROM job
WHERE tenant_id = :t AND status = 'PENDING' AND run_at <= now()
ORDER BY run_at
FOR UPDATE SKIP LOCKED
LIMIT 100;
```

---

## B. Message & Signal orqali jarayonlar aloqasi

### 1. Feature nomi
Tashqi tizimdan message/signal bilan kutayotgan instance'ni uyg‘otish (correlation).

### 2. Qaysi jadvallar yoqadi
`event_subscription` (`MESSAGE`/`SIGNAL`) + `process_instance` (`business_key`) + `execution_token` + `message_correlation` + `tenant`.

### 3. Qanday ishlaydi
1. Token intermediate catch / receive → `event_subscription`, token `WAITING`.
2. Tashqi servis `business_key + event_name` yuboradi.
3. MESSAGE → bitta instance; SIGNAL → barcha mos obunalar.
4. Correlate: token `ACTIVE`, subscription o‘chadi, `message_correlation` audit.

### 4. Real misol
Skoring servisi tayyor bo‘lgach `SCORING_READY` message'ini `request_id=6004` ga yuboradi — conveyor davom etadi.

### 5. Engine kodida qayerda
**Event Handler** (correlate API) → **Token Engine**.

**Sekvens**
```
Scoring API → POST correlate(name=SCORING_READY, business_key=6004)
Event Handler → SELECT event_subscription JOIN process_instance
→ token WAITING→ACTIVE, DELETE subscription, INSERT message_correlation
→ Token Engine.run()
```

**SQL**
```sql
SELECT es.*
FROM event_subscription es
JOIN process_instance pi ON pi.id = es.instance_id
WHERE es.type = 'MESSAGE'
  AND es.event_name = :name
  AND pi.business_key = :bk
  AND pi.tenant_id = :t;
```

---

## C. External Task pattern (mikroservis worker'lar)

### 1. Feature nomi
Og‘ir/uzoq ishlarni tashqi worker'larga chiqarish (Camunda External Task uslubi).

### 2. Qaysi jadvallar yoqadi
`external_task` (+ `execution_token`, `process_instance`).

### 3. Qanday ishlaydi
1. ServiceTask `type=external` → `external_task` (`CREATED`, `topic`).
2. Worker topic bo‘yicha **fetch&lock** (`worker_id`, `lock_expiry_at`).
3. Complete → token oldinga; crash → `lock_expiry_at < now()` boshqa worker oladi.
4. Status: `CREATED` → `LOCKED` → `COMPLETED` / `FAILED`.

### 4. Real misol
PDF passport/scan parsing — `pdf-extract` topic, `request_id=7000`.

### 5. Engine kodida qayerda
**External Task API** (REST) + token resume.

**Sekvens**
```
Token Engine → INSERT external_task (topic=pdf-extract)
Worker → fetch&lock → process PDF → complete
API → UPDATE COMPLETED → Token Engine davom
```

**SQL**
```sql
UPDATE external_task
SET status = 'LOCKED', worker_id = :w, lock_expiry_at = now() + interval '5 min'
WHERE id IN (
  SELECT id FROM external_task
  WHERE topic = :topic
    AND (status = 'CREATED' OR (status = 'LOCKED' AND lock_expiry_at < now()))
  ORDER BY created_at
  FOR UPDATE SKIP LOCKED
  LIMIT :n
)
RETURNING *;
```

---

## D. Ishonchli job bajarilishi (reliability)

### 1. Feature nomi
At-least-once delivery + idempotency + retry + dead letter.

### 2. Qaysi jadvallar yoqadi
`job` (`idempotency_key`, `lock_expiry_at`, `max_attempts`, `attempts`) + `dead_letter_job`.

### 3. Qanday ishlaydi
1. Async serviceTask / timer → `job` + RabbitMQ (yoki in-process queue).
2. Redelivery: `idempotency_key` UNIQUE takror bajarishni bloklaydi.
3. Xato: `attempts++`, `status=RETRY`, backoff `run_at`.
4. `attempts >= max_attempts` → `dead_letter_job`ga ko‘chirish; admin re-enqueue.

### 4. Real misol
Skoring API 3 marta HTTP 500 → DLQ → admin config tuzatib qayta ishga tushiradi (`request_id=4888`).

### 5. Engine kodida qayerda
**Job Executor / Consumer** + poller crash recovery (`lock_expiry_at`).

**Sekvens**
```
Token Engine → INSERT job (idempotency_key) → enqueue
Consumer → lock → execute connector
fail → attempts++, RETRY / max → INSERT dead_letter_job, DELETE/UPDATE job
Admin → re-enqueue from DLQ
```

**SQL**
```sql
-- max attemptsdan oshganda DLQ ga ko'chirish
WITH failed AS (
  UPDATE job SET status = 'FAILED'
  WHERE id = :jobId AND attempts >= max_attempts
  RETURNING *
)
INSERT INTO dead_letter_job (id, original_job_id, instance_id, type, payload, attempts, error_message)
SELECT gen_random_uuid()::text, id, instance_id, type, payload, attempts, :err
FROM failed;
```

---

## E. Multi-instance (forEach / parallel)

### 1. Feature nomi
Bir node ustida N marta (parallel yoki ketma-ket) — masalan har kafil uchun skoring.

### 2. Qaysi jadvallar yoqadi
`execution_token` (`mi_total`, `mi_completed`, `mi_active`, `parent_multi_instance_id`) + bola tokenlar.

### 3. Qanday ishlaydi
1. MI boshlanadi: parent token `mi_total=N`, N bola token yaratiladi.
2. Har bola tugaganda `mi_completed++` (`mi_active--`).
3. `mi_completed == mi_total` → MI yopiladi, parent oldinga.

### 4. Real misol
3 kafil — har biriga alohida KATM/skoring; hammasi tugagach umumiy qaror gateway.

### 5. Engine kodida qayerda
**Token Engine** (MI join/split; hozirgi engine'da asosiy parallel gateway bor, to‘liq MI — keyingi bosqich).

**Sekvens**
```
Token Engine → CREATE N child tokens (mi_total=3)
Child completes → UPDATE parent SET mi_completed = mi_completed+1
RETURNING → if mi_completed=mi_total → advance parent
```

**SQL**
```sql
UPDATE execution_token
SET mi_completed = mi_completed + 1,
    mi_active = GREATEST(mi_active - 1, 0)
WHERE id = :parentMiTokenId
RETURNING mi_completed, mi_total;
```

---

## F. Variable versiyalash & audit

### 1. Feature nomi
O‘zgaruvchi tarixi — "3-qadamda `credit_score` nima edi?"

### 2. Qaysi jadvallar yoqadi
`token_variable` (`revision`, `scope`) + `token_variable_history` + trigger `trg_token_variable_history`.

### 3. Qanday ishlaydi
1. `putAll` → `token_variable` INSERT/UPDATE.
2. UPDATE da qiymat o‘zgarsa trigger eski qiymatni history'ga yozadi, `revision++`.
3. `scope`: INSTANCE / TOKEN / GLOBAL.

### 4. Real misol
`credit_score` skoringdan keyin 620, qayta hisobdan keyin 655 — ikkala qiymat tarixda.

### 5. Engine kodida qayerda
**Token Engine** → `VariableStorePort`; tarix — **DB trigger** (ilova emas).

**Sekvens**
```
Connector → variables.put(credit_score=655)
JDBC UPDATE token_variable
→ trg_token_variable_history → INSERT history (old=620, revision=0), NEW.revision=1
```

**SQL**
```sql
SELECT old_value_text, revision, changed_at
FROM token_variable_history
WHERE instance_id = :i AND name = 'credit_score' AND revision = :r;
```

---

## G. Incident management (xatolik boshqaruvi)

### 1. Feature nomi
Ishlab turgan instance'dagi xatolikni ochiq incident sifatida boshqarish (resolve → retry).

### 2. Qaysi jadvallar yoqadi
`incident` + `execution_token.incident_id` / `execution_token_state.incident_id` + ixtiyoriy `job`.

### 3. Qanday ishlaydi
1. Job/connector xato → `incident` (`OPEN`, type `CONNECTOR_ERROR`/`FAILED_JOB`).
2. `execution_token.incident_id` to‘ldiriladi; token `FAILED`/`WAITING`.
3. Admin resolve → job qayta enqueue, token davom, incident `RESOLVED`.

### 4. Real misol
Skoring endpoint noto‘g‘ri → `CONNECTOR_ERROR` → admin URL tuzatib resolve (`request_id=4888`).

### 5. Engine kodida qayerda
**Incident Manager** + Job Executor xato yo‘li; circular FK — plan 25 §3 (ALTER in `004-auth-stats`).

**Sekvens**
```
Executor fail → INSERT incident (OPEN) → UPDATE execution_token.incident_id
Admin UI → resolve → job PENDING → token ACTIVE → incident RESOLVED
```

**SQL**
```sql
SELECT i.id, i.type, i.message, i.created_at, pi.business_key
FROM incident i
JOIN process_instance pi ON pi.id = i.instance_id
WHERE i.tenant_id = :t AND i.status = 'OPEN'
ORDER BY i.created_at DESC;
```

---

## H. Jarayon versiyasini yangilash (migration)

### 1. Feature nomi
Ishlab turgan instance'ni yangi BPMN versiyasiga ko‘chirish.

### 2. Qaysi jadvallar yoqadi
`process_instance_migration` + `process_definition` (`version`, `is_latest`) + `execution_token` / `execution_token_state`.

### 3. Qanday ishlaydi
1. Yangi definition deploy (`version+1`, `is_latest=true`).
2. Migration: `mapping` (eski node → yangi node).
3. `instance.definition_id` yangilanadi; token `current_node_id` mapping bo‘yicha.
4. `process_instance_migration` audit qatori.

### 4. Real misol
v1'da skor chegarasi 620, v2'da 650 — ochiq arizalarni v2'ga ko‘chirish.

### 5. Engine kodida qayerda
Migration API / admin tool (katalog + token rewrite); **Definition Registry** cache invalidate.

**Sekvens**
```
Admin → migrate(instance, sourceDef, targetDef, mapping)
→ UPDATE process_instance.definition_id
→ UPDATE execution_token.current_node_id via mapping
→ INSERT process_instance_migration
```

**SQL**
```sql
INSERT INTO process_instance_migration (
  id, instance_id, source_definition_id, target_definition_id, mapping, migrated_by
) VALUES (
  :id, :instanceId, :src, :tgt, :mapping::jsonb, :user
);
```

---

## I. User task — assignment, delegation, escalation

### 1. Feature nomi
Inson qarori: claim, delegate, due_date eskalatsiya, forma submit.

### 2. Qaysi jadvallar yoqadi
`user_task` (`assignee`, `candidate_groups`/`users`, `delegated_to`, `escalated_at`, `due_date`, `priority`, `form_key`, `submitted_data`) + timer `job` (eskalatsiya).

### 3. Qanday ishlaydi
1. UserTask node → `user_task` yaratiladi, token `WAITING`.
2. Candidate group ko‘radi → claim (`assignee`, `claim_time`).
3. Delegate → `delegated_to`; `due_date` o‘tsa timer-job `escalated_at`.
4. Complete → `submitted_data`, token `ACTIVE`.

### 4. Real misol
Ariza `credit-officers` guruhiga; 2 soatda claim bo‘lmasa boshliqqa eskalatsiya (`request_id=6004`).

### 5. Engine kodida qayerda
**Token Engine** (userTask yaratish) + Task API + Job Poller (escalation timer).

**Sekvens**
```
Token Engine → INSERT user_task (candidate_groups={credit-officers})
Officer → claim → complete(submitted_data)
→ token ACTIVE → Token Engine
```

**SQL**
```sql
SELECT *
FROM user_task
WHERE tenant_id = :t AND completed = false
  AND (
    assignee = :u
    OR candidate_users @> ARRAY[:u]::varchar[]
    OR candidate_groups && :myGroups::varchar[]
  )
ORDER BY priority DESC, due_date NULLS LAST;
```

---

## J. Ruxsatlar (authorization)

### 1. Feature nomi
Kim qaysi definition/instance/task ustida START/CLAIM/READ qila oladi.

### 2. Qaysi jadvallar yoqadi
`process_authorization` + `tenant` (+ `group_entity`).

### 3. Qanday ishlaydi
1. Qoida: `principal (USER|GROUP|ROLE) × resource_type × resource_id × permission`.
2. `resource_id='*'` — shu turdagi barcha resurs.
3. Har API chaqiruvida Auth Guard tekshiradi.

### 4. Real misol
`credit-officers` `TUNE_CREDIT_REQUEST`ni START qila oladi; `auditors` faqat READ.

### 5. Engine kodida qayerda
**Auth Guard** (REST filter / service), engine oqimidan oldin.

**Sekvens**
```
Client → API start(processKey, business_key=4888)
Auth Guard → SELECT process_authorization …
allow → Token Engine start instance
```

**SQL**
```sql
SELECT 1
FROM process_authorization
WHERE tenant_id = :t
  AND principal_id IN (:userId, :groupIds)
  AND resource_type = 'PROCESS_DEFINITION'
  AND permission = 'START'
  AND resource_id IN (:definitionId, '*')
LIMIT 1;
```

---

## K. Monitoring & statistika

### 1. Feature nomi
Kunlik throughput / duration / SLA + instance debug izi.

### 2. Qaysi jadvallar yoqadi
`process_stats_daily` + `execution_log` + `execution_listener_log` + `execution_token_state`.

### 3. Qanday ishlaydi
1. Kunlik aggregator instance/token_state'dan agregat hisoblaydi → `process_stats_daily`.
2. Bitta instance: `execution_token_state` (yo‘l) + `execution_log` (connector/gateway) + `execution_listener_log`.

### 4. Real misol
Bugun 1240 ariza, 87% avto-tasdiq, o‘rtacha 4.2s; 1 ta SLA breach.

### 5. Engine kodida qayerda
**Stats Aggregator** (`@Scheduled`); debug — Token Engine yozgan loglar.

**Sekvens**
```
Night job → aggregate process_instance / execution_token_state
→ UPSERT process_stats_daily
Ops → dashboard SELECT stats; support → SELECT execution_log WHERE instance_id=…
```

**SQL**
```sql
INSERT INTO process_stats_daily (
  id, definition_id, stat_date, started_count, completed_count, failed_count, avg_duration_ms
)
SELECT
  gen_random_uuid()::text,
  definition_id,
  CURRENT_DATE - 1,
  COUNT(*) FILTER (WHERE created_at::date = CURRENT_DATE - 1),
  COUNT(*) FILTER (WHERE status = 'COMPLETED' AND ended_at::date = CURRENT_DATE - 1),
  COUNT(*) FILTER (WHERE status = 'FAILED' AND ended_at::date = CURRENT_DATE - 1),
  AVG(EXTRACT(EPOCH FROM (ended_at - created_at)) * 1000)::int
FROM process_instance
GROUP BY definition_id
ON CONFLICT (definition_id, stat_date) DO UPDATE
SET started_count = EXCLUDED.started_count,
    completed_count = EXCLUDED.completed_count,
    failed_count = EXCLUDED.failed_count,
    avg_duration_ms = EXCLUDED.avg_duration_ms;
```

---

## L. Multi-tenancy

### 1. Feature nomi
Bitta engine — ko‘p bank/mijoz; qator darajasida izolyatsiya.

### 2. Qaysi jadvallar yoqadi
`tenant` + deyarli barcha runtime/katalog jadvallardagi `tenant_id`.

### 3. Qanday ishlaydi
1. Har so‘rovda `tenant_id` konteksti (header / JWT).
2. Unique'lar tenant bo‘yicha: `(tenant_id, business_key)`, `(tenant_id, process_key, version)`.
3. FK `tenant_id → tenant` **RESTRICT** — tenant tasodifan o‘chmasin.

### 4. Real misol
Bank A va Bank B bir engine'da; A operatori B ning `request_id` arizasini ko‘rmaydi.

### 5. Engine kodida qayerda
**Auth Guard** + barcha repository SELECT/INSERT'larda `WHERE tenant_id = :currentTenant`.

**Sekvens**
```
Request (X-Tenant: bank-a) → Auth Guard → repos filter tenant_id
→ Token Engine faqat shu tenant instance'lari
```

**SQL**
```sql
SELECT *
FROM process_instance
WHERE tenant_id = :currentTenant
  AND business_key = :requestId;
```

---

## Feature × Jadval matritsa

| Feature | job | event_subscription | external_task | dead_letter_job | execution_token | token_variable(+history) | incident | process_instance_migration | user_task | process_authorization | process_stats_daily | execution_log | tenant |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| A Timer | ✅ | ✅ | | | | | | | | | | | ✅ |
| B Message/Signal | | ✅ | | | ✅ | | | | | | | | ✅ |
| C External Task | | | ✅ | | ✅ | | | | | | | | |
| D Job reliability | ✅ | | | ✅ | | | | | | | | | ✅ |
| E Multi-instance | | | | | ✅ | | | | | | | | |
| F Variable audit | | | | | | ✅ | | | | | | | |
| G Incident | ✅ | | | | ✅ | | ✅ | | | | | | ✅ |
| H Migration | | | | | ✅ | | | ✅ | | | | | |
| I User task | ✅ | | | | ✅ | | | | ✅ | | | | ✅ |
| J Authorization | | | | | | | | | | ✅ | | | ✅ |
| K Monitoring | | | | | ✅ | | | | | | ✅ | ✅ | |
| L Multi-tenancy | ✅ | ✅ | | | | | ✅ | | ✅ | ✅ | | | ✅ |

Qo‘shimcha bog‘liq jadvallar (matritsada alohida ustun yo‘q, lekin feature bilan bog‘liq):

| Feature | Qo‘shimcha |
|---|---|
| A Timer | `timer_definition` |
| B Message | `message_correlation`, `process_instance.business_key` |
| G Incident | `execution_token_state.incident_id` |
| H Migration | `process_definition.version` / `is_latest` |
| I User task | `group_entity` (candidate groups katalogi) |
| K Monitoring | `execution_listener_log`, `execution_token_state` |
| L Multi-tenancy | barcha `tenant_id` ustunlari |

---

## Bog‘liq hujjatlar

- Schema ERD / jadval indeksi: [`README.md`](./README.md)
- DBML (dbdiagram.io): [`bpms-schema.dbml`](./bpms-schema.dbml)
- DDL: `bpms-persistence-jpa/src/main/resources/db/changelog/changes/`
