# BPMS — yangi versiya (new engine) rejalari

> Bu folder **faqat yangi BPMS versiyasiga** (`bpms/bpms-new-backend/`) oid rejalarni saqlaydi.
> Asosiy/eski mahsulot tasklari `plans/` ildizida (`01-10` + `BPMS_OPTIMIZATION_CONTEXT.md`) — ular bilan
> aralashtirilmaydi. Yangi versiya bo'yicha barcha yangi reja **shu folderga** yoziladi.

## Kontekst (self-contained)
- **Kod:** `bpms/bpms-new-backend/` — multi-module, **Java 21 + Spring Boot 3.5.14**, Hexagonal (Ports & Adapters) + SPI,
  Virtual Threads. Modullar: `bpms-core / spi / expression / parser-camunda / engine / persistence-jpa / queue-rabbit /
  server / connectors-creditconveyer`.
- **Infra izolyatsiya:** o'z Postgres (`bpms_new`, port 5433) + o'z RabbitMQ (5673). Eski bpms DB/RabbitMQ'siga ULANMAYDI.
- **Guardrail:** `rcc/.cursor/rules/bpms-new-engine.mdc` (+ `rcc/.cursorrules`) — kod faqat `bpms-new-backend/`da;
  eski `bpms/liveScoring-bpms-backend/` = **READ-ONLY** (faqat dialekt/parity uchun o'qiladi).
- **Compat tamoyili:** yangi parser eski Camunda dialektini takrorlaydi → eski `.bpmn` sxemalar o'zgarmasdan yuklanadi.

## Rejalar indeksi
> Holat — **shu sessiya bo'yicha taxminiy**; aniq holatni kod/testdan tekshiring.

| # | Reja | Mavzu | Holat |
|---|---|---|---|
| 11 | `11-bpms-engine-strategy.md` | Engine strategiyasi (optimize vs custom vs embedded) — qaror hujjati | 📄 qaror |
| 12 | `12-bpms-new-engine.md` | Yangi engine (yonma-yon, Hexagonal, type-safe dispatch, VT) | ✅ asos |
| 13 | `13-bpms-platform-standalone.md` | Umumiy platforma vizyoni (SPI, discovery/catalog, ko'p-format) | 📄 vizyon |
| 14 | `14-bpms-schema-compatibility.md` | Eski Camunda sxemalarni minimal o'zgarish bilan yuklash (CamundaCompatParser) | ✅ parser+corpus |
| 15 | `15-bpms-new-engine-runnable.md` | Ishga tushiriladigan: docker-compose + persistence + deploy + execute + start + async (RabbitMQ) | ✅ asosan |
| 16 | `16-fix-eav-variable-typing.md` | EAV variable typing bug (decimal→long crash) tuzatish | 🔧 tekshirilsin |
| 17 | `17-definition-cache-no-reparse.md` | Runtime re-parse'ni yo'qotish (DefinitionRegistry cache, XML canonical) | ✅ cache bor |
| 18 | `18-port-creditconveyer-v9.md` | creditConveyer **v9** connectorlarini ko'chirish (SPI) | ✅ (20 kengaytirdi) |
| 19 | `19-db-execution-log.md` | Ijro logini bazaga yozish (execution_log + ExecutionLogPort) | 🔧 qisman |
| 20 | `20-import-v8-v9-connectors.md` | **v6/v7 + v8 + v9** barcha connector/servis importi + real test sxemalar (4888/6004/7000) | ✅ import+test |
| 21 | `21-async-process-start.md` | START'ni async qilish (DB → RabbitMQ → consumer) | 📝 tekshirilsin |
| 22 | `22-fix-gateway-unconditional-flow.md` | Exclusive/inclusive gateway: shartsiz chiquvchi flow implicit default (merge gateway fix) | 📝 kutilmoqda |
| 23 | `23-execution-token-state-model.md` | `execution_token_history` → `execution_token_state` + listener trace (BEFORE/AFTER execute) | 📝 kutilmoqda |
| 24 | `24-flyway-to-liquibase-migration.md` | Flyway → Liquibase (amalda **XML** changelog qilib bajarildi) | ✅ bajarilgan |
| 25 | `25-schema-v3-ddl-migrations.md` | v3 sxema (~29 jadval) DDL + FK/CHECK/partition/trigger/seed | ✅ implement (`changes/001-004.xml`) |
| 26 | `26-features-documentation.md` | `FEATURES.md` — sxema qaysi biznes-feature'larni yoqadi (A–L) | ✅ `docs/FEATURES.md` bor |
| 27 | `27-instance-terminate-and-runaway-guard.md` | Instance TERMINATE + recursive/loop guardrail (step-budjet, cooperative cancel) | 📝 kutilmoqda |
| 28 | `28-timer-message-signal-events.md` | Timer/Message/Signal event (P0: intermediate timer catch `timeDuration` — real polling loop) | 📝 kutilmoqda |
| 29 | `29-dod-gap-close.md` | 22/25/27 DoD bo'shliqlarini yopish (test + node-revisit guard verify + README) | 📝 kutilmoqda |
| 30 | `30-start-form-validation-business-key.md` | Start-forma validatsiyasi + business key (21 §2.1); 10/10 korpus sxema ishlatadi | 📝 kutilmoqda |
| 31 | `31-human-workflow-usertask-manualtask.md` | Human-workflow: userTask/manualTask/terminateEnd/initiator (HR approval, regional_vacation) | 📝 kutilmoqda |
| 32 | `32-remaining-bpmn-elements.md` | Qolgan 7 element: boundary/intermediateThrow/callActivity/subProcess/receive/sendTask/businessRuleTask | ✅ done (DMN deferred) |
| 33 | `33-refactor-god-classes.md` | God-class refactor: ExecutionEngine (793)→NodeBehavior, JpaPersistenceAdapter (685)→port bo'yicha | 🔧 engine NodeBehavior ✅; JPA split kutilmoqda |
| 34 | `34-public-api-test-process.md` | Public API test protsesi (http-json-get connector + valyuta kursi bpmn) — engine end-to-end sinovi | 📝 kutilmoqda |

> **ADR:** `docs/adr/ADR-001-execution-listeners-vs-service-tasks.md` — skript-listener + `bpms.*` vs service-task (qaror).
> **Namuna:** `docs/examples/regional_vacation_bestpractice.bpmn` — HR protsesning best-practice refactor cloni.

## Tavsiya yo'l xaritasi (roadmap)
1. **Arxitektura:** 11 → 12/13 (vizyon) → 14 (compat).
2. **Ishga tushirish:** 15 (docker/persistence/deploy/execute/start) → 16 (EAV fix) → 17 (cache) → 19 (log).
3. **Domen:** 18 → 20 (v6/v7/v8/v9 connectorlar) — real protseslarni sinash.
4. **Persistence/model:** 24 (Liquibase) → 25 (v3 DDL) → 26 (FEATURES) — ✅ bajarilgan.
5. **Async/engine to'g'riligi:** 21 (async start parity, tekshirilsin) → 22 (gateway shartsiz-flow, yuqori ustuvorlik) → 23 (token_state + listener trace).
6. **Event'lar:** 28 (timer/message/signal — P0 timer real korpus polling'i uchun majburiy).
7. **DoD tozalash:** 29 (22/25/27 bo'shliqlari + README).
8. **Korpus to'liq ishlashi:** 30 (start-forma + business key) → **22+28+30 bilan 4888/6004/7000 end-to-end yuradi** (katta bosqich).
9. **Keyingi:** outbox poller, request-scoped multi-tenancy, call-activity (27 spawn-guard'ni ochadi), boundary event, catalog/validate API, shadow-run parity.

## Test sxemalari (compat-corpus)
- `TUNE_CREDIT_REQUEST_4888` — V6/V7 · `TUNE_CREDIT_REQUEST_6004` — V8 · `TUNE_CREDIT_REQUEST_7000` — V8+V9.
- Demo: `docs/examples/demo-credit-scoring.bpmn`, `credit-v9-smoke.bpmn`.

## Keyingi mumkin bo'lgan rejalar (hali yozilmagan)
- Outbox poller (async ishonchlilik), timer/message/boundary event, multi-instance parity, catalog/validate API
  (vizual-modeler), shadow-run parity (yangi ≡ eski engine), boshqa versiya (v2/v3/v5) connectorlari.
