# Plan 15 — bpms-new-backend: ishga tushiriladigan holatga keltirish (docker-compose + upload/deploy + start + execute)

> **Maqsad:** mavjud scaffold (parser tayyor) ustiga **ishlaydigan vertikal qatlam** qurish:
> `docker compose up` → sxemani **upload/deploy** qilish → protses instansiyasini **start** qilish → engine uni
> **ijro** etadi. Ya'ni "parse qiladi" dan "haqiqatan ishlaydi" ga o'tish.
>
> **Bu reja Cursor tomonidan bajariladi.** Har faza alohida, tartib bilan. Faza tugagach test/DoD tekshiriladi,
> keyin keyingisiga o'tiladi (butun rejani bir zarbada emas).
>
> Bog'liq: `plans/14-bpms-schema-compatibility.md` (dialekt/compat faktlari — §1), `plans/12`, `plans/13` (arxitektura),
> `bpms/bpms-new-backend/PHASE0-INVENTORY.md` (ochiq bloklovchilar).

---

## 0. Hozirgi holat (grounding — shu ustiga quriladi)

Tasdiqlangan (kod o'qib): `bpms/bpms-new-backend/` — Maven multi-module, **Boot 3.5.14 / Java 21 / Virtual Threads**.
Mavjud modullar:
- `bpms-core` — pivot model (`ProcessDefinition`, `FlowNode` + barcha node turlari, `SequenceFlow`, `MultiInstanceSpec`,
  `ListenerSpec`, `ConnectorImplementation`, `FormFieldSpec`, `CompatWarning`, `ParseResult`).
- `bpms-spi` — `ProcessDefinitionParser`, `SourceFormat`.
- `bpms-expression` — **SpEL evaluator + `isExprStr` heuristikasi** (eski dialekt bilan parity; testlar yashil).
- `bpms-parser-camunda` — `CamundaCompatParser` + `ElementMapper` (flow-node walk, qamrab olinmaganga `CompatWarning`).
  10 real credit-conveyor `.bpmn` `warning=0` bilan parse bo'ladi (`compat-corpus/credit-conveyor/`).
- `bpms-server` — `ParseController` (`/api/v1/schema/parse`, `/parse-text`), port **8090**, actuator health/info, springdoc.

**Yo'q (shu reja qo'shadi):** persistence (DB), execution engine (token ijrosi), connector SPI+registry, deploy/start
REST, docker-compose/Dockerfile.

## 0.1 Qat'iy qoidalar (takror — buzilmaydi)
- Kod **faqat `bpms/bpms-new-backend/`** da. `bpms/liveScoring-bpms-backend/` — READ-ONLY (faqat dialekt faktlari uchun o'qish).
- Stack: **Java 21 + Spring Boot 3.5.14** (`jakarta.*`). Hexagonal + SPI. Type-safe connector registry
  (`Map<String,Connector>`, `getBean(String)` YO'Q). God-class yo'q.
- **Infra IZOLYATSIYA (qat'iy):** yangi engine **o'z** Postgres'i + **o'z** RabbitMQ'si bilan ishlaydi (alohida
  konteyner/port/parol/vhost). Eski bpmsning DB yoki RabbitMQ'siga (yoki boshqa har qanday eski infra'ga) **ulanmaydi**.
  Umumiy jadval/queue yo'q.
- **Phase 0 bloklovchilarni taxmin qilma** (nested-property Variant A/B; boshqa guruh sxemalari). Ular kerak bo'lsa —
  to'xta va so'ra. Bu reja **credit-conveyor dialekti ∩ korpus** doirasida ishlaydi.

---

## 1. Yangi modullar (plan 13 §2 ga mos)

```
bpms-new-backend/
├── bpms-core           (bor)
├── bpms-spi            (bor — + Connector, ConnectorProvider, out-portlar qo'shiladi)
├── bpms-expression     (bor)
├── bpms-parser-camunda (bor)
├── bpms-engine         (YANGI — orkestratsiya: token ijrosi, gateway, dispatch)
├── bpms-persistence-jpa(YANGI — default outbound adapter: Postgres/JPA + EAV)
└── bpms-server         (bor — + deploy/start controller, DI, config)
```
> `bpms-core`/`bpms-engine` framework'siz sof domen (Spring yo'q); Spring faqat `bpms-server` + adapterlarda.

---

## 2. Faza A — Infra: docker-compose + Dockerfile + config (ishga tushishi)

**Maqsad:** `docker compose up` → Postgres + ilova ko'tariladi, `/actuator/health` = UP.

1. **Dockerfile** (`bpms-new-backend/Dockerfile`, multi-stage):
   - build: `maven:3.9-eclipse-temurin-21` → `mvn -q -DskipTests package` (butun reactor);
   - run: `eclipse-temurin:21-jre` → `bpms-server/target/bpms-server-*.jar` ni `app.jar` qilib ko'chirib, `java -jar`.
2. **docker-compose.yml** (`bpms-new-backend/docker-compose.yml`):
   ```yaml
   services:
     postgres:
       image: postgres:16
       environment: { POSTGRES_DB: bpms_new, POSTGRES_USER: bpms, POSTGRES_PASSWORD: bpms }
       ports: ["5433:5432"]              # eski DB bilan to'qnashmasin (5433 tashqi)
       volumes: ["bpms_new_pg:/var/lib/postgresql/data"]
       healthcheck: { test: ["CMD-SHELL","pg_isready -U bpms -d bpms_new"], interval: 5s, retries: 10 }
     rabbitmq:
       image: rabbitmq:3-management
       environment: { RABBITMQ_DEFAULT_USER: bpms, RABBITMQ_DEFAULT_PASS: bpms }
       ports: ["5673:5672", "15673:15672"]   # eski rabbitmq (5972/19672) bilan to'qnashmasin
       healthcheck: { test: ["CMD","rabbitmq-diagnostics","-q","ping"], interval: 10s, retries: 10 }
     app:
       build: .
       depends_on:
         postgres: { condition: service_healthy }
         rabbitmq: { condition: service_healthy }
       environment:
         SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/bpms_new
         SPRING_DATASOURCE_USERNAME: bpms
         SPRING_DATASOURCE_PASSWORD: bpms
         SPRING_RABBITMQ_HOST: rabbitmq
         SPRING_RABBITMQ_PORT: 5672
         SPRING_RABBITMQ_USERNAME: bpms
         SPRING_RABBITMQ_PASSWORD: bpms
       ports: ["8090:8090"]
       healthcheck: { test: ["CMD-SHELL","wget -qO- http://localhost:8090/actuator/health || exit 1"], interval: 10s, retries: 10 }
   volumes: { bpms_new_pg: {} }
   ```
   > **Bu — yangi engine'ning O'Z RabbitMQ'si (eski bpmsdan TO'LIQ IZOLYATSIYA):** alohida konteyner, alohida
   > user/parol (`bpms/bpms`), alohida port (5673/15673), alohida vhost + queue-namespace. Yangi engine **hech qachon**
   > eski bpmsning RabbitMQ yoki DB'siga ulanmaydi. Ijro patterni eski `TasksJobExecutionConsumer`ga o'xshaydi, lekin
   > **infra mustaqil**. Async adapter Faza G'da (§7.5); `JobQueuePort` orqali sinxron in-process (VT) rejim ham bor
   > (smoke-test/IDE uchun).
3. **application.yml**: `spring.datasource.*` env'dan (`SPRING_DATASOURCE_*`), Flyway yoqilgan, actuator health.
   `application-local.yml` — localhost:5433 bilan (docker'siz IDE'dan run uchun).
4. **README** ga: `docker compose up --build` + smoke-test buyruqlari (§9).

**DoD-A:** `docker compose up --build` → **uchala** konteyner (postgres + rabbitmq + app) healthy; `GET http://localhost:8090/actuator/health` = `{"status":"UP"}`; Postgres va RabbitMQ'ga ulanish bor (health `db` + `rabbit` UP).

---

## 3. Faza B — Persistence (JPA + EAV, plan 13 §7)

**Maqsad:** definition/instance/token/variable saqlash. `VariableStorePort` orqasida — default EAV.

1. **`bpms-persistence-jpa`** moduli. Flyway migratsiya (`V1__init.sql`) — jadvallar:
   ```
   process_definition(id, key, name, version, source_format, bpmn_xml, parsed_json jsonb, created_at)
   process_instance(id, definition_id, business_key, status, created_at, ended_at)
   execution_token(id, instance_id, current_node_id, status, parent_multi_instance_id)
   token_variable(id, instance_id, name, type, value_text, value_json jsonb, ...)   -- typed EAV
   job(id, instance_id, token_id, type, payload jsonb, status, attempts, run_at)     -- §8 async uchun tayyor
   ```
   Indekslar: `process_definition(key,version)`, `process_instance(business_key)`, `execution_token(instance_id,status)`.
2. **Outbound portlar** (`bpms-spi`): `DefinitionRepositoryPort`, `InstanceRepositoryPort`, `TokenRepositoryPort`,
   `VariableStorePort`, `ClockPort`. JPA adapter ularni implementatsiya qiladi.
3. **EAV (VariableStorePort default) — canonical tip-xaritasi:** o'zgaruvchi `token_variable` (1 qator/o'zgaruvchi,
   `unique(instance_id,name)`, UPSERT). `type` diskriminatori + `value_text`/`value_json`. Tip-xaritasi (**kasrni
   `long` qilmang** — `NumberFormatException` beradi):
   - `Integer/Long/BigInteger` → `long`; **`Double/Float/BigDecimal` → `double`** (o'qishda `BigDecimal`, pul aniqligi);
   - `Boolean` → `boolean`; `TemporalAccessor` → `date` (ISO-8601 satr); `String` → `string`; `Map/List/obyekt` → `json` (jsonb).
   - Pul uchun `ObjectMapper.USE_BIG_DECIMAL_FOR_FLOATS` yoqilsin. Batafsil + fix: `plans/16-fix-eav-variable-typing.md`.
   - Eski `InstanceVariable*` (10 jadval) → shu 1 jadval + type (plan 14 §1.7). Domen boshqa store xohlasa portni almashtiradi.

**DoD-B:** Flyway migratsiya konteynerda qo'llanadi; JPA repozitoriylar CRUD unit/IT testdan o'tadi (Testcontainers yoki H2-profile — Postgres afzal).

---

## 4. Faza C — Deploy / upload REST

**Maqsad:** BPMN yuklab, parse qilib (**warning bo'lsa deploy bloklanadi**), versiyalab saqlash.

1. `DeployController` (`bpms-server`), `/api/v1/process-definitions`:
   - `POST` (consumes XML yoki multipart) → `CamundaCompatParser.parse(bytes)`:
     - `result.hasWarnings()` → **HTTP 422** + warning ro'yxati (jim deploy YO'Q — plan 14 §6 Faza 6 qoidasi);
     - warnings=0 → `ProcessDefinition` ni saqlash: `key` = process id, `version` = mavjud maksimal+1, `bpmn_xml` +
       `parsed_json`. Javob: `{definitionId, key, version}`.
   - `GET /api/v1/process-definitions` — ro'yxat; `GET /{id}` — bitta (parsed model + xml).
2. Inbound port `ProcessEnginePort.deploy(...)` orqali (Hexagonal — controller domenaga to'g'ridan bog'lanmaydi).

**DoD-C:** demo BPMN (§9) upload → 200 + versiya; warning beruvchi buzuq sxema → 422 + aniq warning; DB'da yozuv bor.

---

## 5. Faza D — Engine core (token ijrosi — eng katta qism)

**Maqsad:** deploy qilingan protsessni **haqiqatan ijro** etish. MVP qamrovi (eski dialekt semantikasi, plan 14 §1.5/§2):

1. **`bpms-engine`** — `ExecutionEngine`:
   - Token graf bo'ylab yuradi: `startEvent → ... → endEvent`.
   - **serviceTask** → connector dispatch (2-band).
   - **exclusiveGateway** → shartlar (`bpms-expression` SpEL + `isExprStr`), hech biri mos kelmasa `default` flow
     (plan 14 §1.5). **parallelGateway** → fork/join (barcha kiruvchi token kelmaguncha kutish). inclusive — MVP'da
     Deferred (§8) yoki oddiy holat.
   - **scriptTask** / expression → SpEL evaluator.
   - **userTask** → **wait-state** (token to'xtaydi, instance `WAITING`) — Faza F to'ldiradi (start uchun avval
     serviceTask/gateway/end yetarli).
   - **sequenceFlow** shart baholash; **end** → token yopiladi; hamma token yopilsa instance `COMPLETED`.
   - **Executor:** Virtual Threads (bloklovchi connector I/O).
2. **Connector SPI + registry** (`bpms-spi` + `bpms-engine`):
   ```java
   public interface Connector { String id(); ConnectorResult execute(ConnectorContext ctx); ConnectorDescriptor describe(); }
   public interface ConnectorProvider { Collection<Connector> connectors(); }
   // ConnectorRegistry: Map<String,Connector>, startup'da yig'iladi; noto'g'ri id → startup xatosi (getBean YO'Q).
   ```
   - `ConnectorContext`: token, input o'zgaruvchilari (`camunda:inputParameter`), variable store.
   - `ConnectorResult`: output o'zgaruvchilari (`camunda:outputParameter`) — immutable record.
   - **Namuna connector'lar (smoke uchun, `bpms-server` yoki alohida `bpms-connectors-demo`):** `NoOpConnector`
     (id `noop`, hech nima qilmaydi), `EchoConnector` (input'ni output'ga ko'chiradi). Real credit-conveyor
     connector'lari — **alohida domen-plug** (bu rejaga kirmaydi; §8).
3. **Listener (start/end)** — MVP'da minimal: expression/script listener ijrosi (`bpms-expression`), script-namespace
   **abstract SPI** (`ScriptContextContributor` — plan 14 §1.6; `bpms.hrms` hardcode YO'Q). Agar murakkab bo'lsa —
   Faza F ga qoldiring, lekin **hardcode qilmang**.
4. **Ijro modeli — `JobQueuePort` (async-ready):** og'ir/tashqi qadam (serviceTask connector) `JobQueuePort` orqali
   yuboriladi. **Default adapter:** in-process (Virtual Threads) — smoke/IDE uchun. **RabbitMQ adapter** Faza G'da
   (§7.5) — eski engine parity. Yadro **portni** biladi, transportni emas (plan 13 §7 pluggable).

**DoD-D:** demo protses (start→serviceTask[noop]→exclusiveGateway→end) yakuniga qadar ijro bo'ladi; token yo'li +
o'zgaruvchilar DB'da; noto'g'ri connectorId startup'da xato beradi. Gateway shart/default testlari yashil.

---

## 6. Faza E — Start + run REST + holat

**Maqsad:** REST orqali instansiya boshlash va holatini ko'rish.

1. `ProcessInstanceController`, `/api/v1/process-instances`:
   - `POST` body `{definitionKey (yoki id), businessKey, variables:{...}}` → instansiya yaratadi, **sinxron** ijro
     etadi (MVP) yoki wait-state'da to'xtaydi; javob `{instanceId, status, currentTokens}`.
   - `GET /{id}` → instansiya holati (status, token'lar, o'zgaruvchilar).
2. Inbound `ProcessEnginePort.start(definitionRef, businessKey, vars)`.

**DoD-E:** `POST /process-instances` (demo key) → `COMPLETED` (yoki serviceTask ishlab, natija o'zgaruvchilari bilan);
`GET /{id}` holatni qaytaradi.

---

## 7. Faza F — userTask (wait) + complete (keyingi)

- userTask token'da to'xtaydi (`WAITING`), `tasks` yozuvi yaratiladi.
- `POST /api/v1/tasks/{id}/complete` body `{variables}` → o'zgaruvchilarni yozadi, token davom etadi.
- Forma sxemasi (FormFieldSpec) — deploy'dan o'qiladi; **runtime forma-to'ldirish** eski frontend konvensiyasiga mos
  (plan bpms-new/01 §5). Nested-property (Variant A/B) hal bo'lmaguncha — flat properties bilan cheklab, murakkab
  form-type'larni **kechiktir**.

---

## 7.5 Faza G — Async ijro (RabbitMQ — eski engine parity)

**Maqsad:** serviceTask connector'lari **navbat orqali async/parallel** ijro etilsin — `TasksJobExecutionConsumer`
patterni bo'yicha, lekin **yangi engine'ning o'z alohida RabbitMQ instansiyasida** (eski bpmsdan izolyatsiya, uning
broker'iga ulanish YO'Q).

1. **`bpms-queue-rabbit`** moduli (`JobQueuePort` RabbitMQ adapteri):
   - serviceTask'ga yetgan token → `job` yoziladi (DB) → RabbitMQ'ga publish (`Jackson2JsonMessageConverter`);
   - `@RabbitListener` consumer (**Virtual Threads**) → connector ijrosi → natija o'zgaruvchilari → token davom etadi.
2. **Resilience:** **retry + backoff**, **idempotentlik** (job `business_key`/token dedup), **manual ack + dedup**,
   har chaqiruvda **timeout** (eski `resilience` qoidalari, plan 12 §3.5).
3. **Config:** `spring.rabbitmq.*` (compose env'dan — **o'z broker'i**, eski bpms'niki EMAS). O'z vhost'i; queue/exchange
   nomlari **namespace** bilan (domen izolyatsiyasi). Eski bpms RabbitMQ'siga ulanish qat'iyan YO'Q.
4. **Rejim:** `JobQueuePort` implementatsiyasi profil bilan tanlanadi (in-process VT ↔ rabbit). Prod = rabbit.

> **Timer eslatma:** eski engine timer'ni ham RabbitMQ **kechiktirilgan xabar** orqali qiladi (`TasksDelayTimer*`
> consumer'lar). §8 dagi timer/boundary event fazasi shu mexanizmga tayanadi.

**DoD-G:** serviceTask connector RabbitMQ consumer'da (VT) ijro bo'ladi; `job` DB'da; retry/idempotentlik testdan
o'tadi; `docker compose` bilan uchidan-uchiga (deploy → start → **async** ijro → COMPLETED) ishlaydi.

---

## 8. Deferred (keyingi rejalar — hozir EMAS)
- **Event/gateway to'liq:** timer (timeDate/Duration/Cycle), message correlation, boundary/intermediate, inclusive
  join, **multi-instance** (seq/parallel) — plan 14 §1 bo'yicha, parity testlari bilan.
- **Catalog/validate API:** `GET /catalog/connectors`, `POST /schema/validate` (vizual-modeler uchun, plan 13 §6).
- **Shadow-run parity:** real credit-conveyor sxemasini ikkala engine'da solishtirish (plan 14 §6 Faza 5) — real
  connector'lar plug bo'lgach.
- **Boshqa domen sxemalari:** `compat-corpus/external/` to'lgach compat kengaytiriladi (plan 14 §4.1(2)).

---

## 9. Runnable smoke-test (yakuniy DoD — o'z-ichida, tashqi bog'liqliksiz)

`bpms-new-backend/compat-corpus/` yoki `src/test/resources` ga **demo BPMN** (`demo-linear.bpmn`):
`startEvent → serviceTask(connectorId="noop") → exclusiveGateway(shart: ${amount > 100} ? end_ok : end_low) → endEvent`.

Qadamlar (README'ga yoziladi):
```
1) docker compose up --build            # postgres + app healthy
2) curl -X POST :8090/api/v1/process-definitions -H "Content-Type: application/xml" --data-binary @demo-linear.bpmn
   → 200 {definitionId, key, version}
3) curl -X POST :8090/api/v1/process-instances -H "Content-Type: application/json" \
        -d '{"definitionKey":"demo-linear","businessKey":"T1","variables":{"amount":150}}'
   → 200 {instanceId, status:"COMPLETED", ...}
4) curl :8090/api/v1/process-instances/{id}   → token end_ok'da, amount=150
```
**Bu ishlasa — engine "ishlatishga tayyor" (MVP).** Real credit-conveyor sxemalari uchun domen connector'lari
alohida plug qilinadi (§8).

---

## 10. Bosqichlar tartibi (Cursor uchun)
A (infra: DB+RabbitMQ) → B (persistence) → C (deploy) → D (engine, sinxron core) → E (start/run) → **G (async RabbitMQ)** →
F (userTask). Har fazadan keyin DoD tekshir, testlar yashil bo'lsin, keyin davom et. Har fazada: avval reja/mavjud
kodni o'qib, nima qilishingni qisqa ayt, keyin yoz.
> Izoh: RabbitMQ infra (Faza A) da bor, lekin async **ijro** (Faza G) sinxron core (D+E) ishlagach ulanadi — shunda
> har qadam alohida test bo'ladi. Xohlasangiz G'ni D bilan birga ham qildirish mumkin.

**Definition of Done (umumiy):**
- [ ] Yangi kod faqat `bpms-new-backend/`; eski bpms 0 fayl diff.
- [ ] `docker compose up --build` → app + postgres healthy.
- [ ] Deploy (warning-gate), start, sinxron ijro ishlaydi; §9 smoke-test o'tadi.
- [ ] Type-safe connector registry; noqto'g'ri connectorId startup'da xato.
- [ ] **Async (Faza G): RabbitMQ** orqali serviceTask connector ijrosi (VT), `JobQueuePort` pluggable, retry/idempotentlik.
- [ ] Gateway/shart semantikasi (default flow, parallel join) testlar bilan.
- [ ] Stack Java 21 + Boot 3.5.14; VT yoqilgan; persistence port orqali (EAV default).
- [ ] Deferred ro'yxati (§8) hujjatlangan; taxmin qilingan Phase-0 qismlar YO'Q.
