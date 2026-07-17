# 04 — ROADMAP va REJALAR INDEKSI

> Holat — **suhbat bo'yicha taxminiy**; aniqni kod/testдан tekshiring. Reja fayllар: `plans/bpms-new-backend/`
> (yoki shu `docs/` yonида).

## Rejalар 11–27
| # | Reja | Mavzu | Holat |
|---|---|---|---|
| 11 | `11-bpms-engine-strategy.md` | Engine strategiyasи (optimize vs custom vs embedded) — qaror | 📄 qaror |
| 12 | `12-bpms-new-engine.md` | Yangi engine (Hexagonal, type-safe dispatch, VT) | ✅ asos |
| 13 | `13-bpms-platform-standalone.md` | Platforma vizyoni (SPI, discovery/catalog, ko'p-format) | 📄 vizyon |
| 14 | `14-bpms-schema-compatibility.md` | Eski Camunda sxemаларни yuklash (CamundaCompatParser) | ✅ parser+corpus |
| 15 | `15-bpms-new-engine-runnable.md` | docker-compose + persistence + deploy + execute + start + async | ✅ asosan |
| 16 | `16-fix-eav-variable-typing.md` | EAV variable typing bug (decimal→long crash) | 🔧 tekshirilsin |
| 17 | `17-definition-cache-no-reparse.md` | DefinitionRegistry cache (XML canonical, re-parse yo'q) | ✅ cache bor |
| 18 | `18-port-creditconveyer-v9.md` | creditConveyer v9 connectorlар (SPI) | ✅ (20 kengaytирди) |
| 19 | `19-db-execution-log.md` | Ijro logини bazага (execution_log + ExecutionLogPort) | 🔧 qisman |
| 20 | `20-import-v8-v9-connectors.md` | v6/v7 + v8 + v9 connectorlар + real sxemалар (4888/6004/7000) | ✅ import+test |
| 21 | `21-async-process-start.md` | START async (DB → RabbitMQ → consumer) | 📝 tekshirilsin* |
| 22 | `22-fix-gateway-unconditional-flow.md` | Exclusive/inclusive gateway: shartсиз flow implicit default | 📝 kutилmoqda |
| 23 | `23-execution-token-state-model.md` | `execution_token_history` → `execution_token_state` + listener trace (BEFORE/AFTER) | 📝 kutилmoqda |
| 24 | `24-flyway-to-liquibase-migration.md` | Flyway → Liquibase — **XML** changelog qilib bajarилди | ✅ bajarилган |
| 25 | `25-schema-v3-ddl-migrations.md` | v3 sxема (~29 jadval) DDL + FK/CHECK/partition/trigger/seed | ✅ implement (changelog `changes/001-004.xml`) |
| 26 | `26-features-documentation.md` | `FEATURES.md` — sxема qайси feature'ларни yoqади (A–L) | ✅ `docs/FEATURES.md` bor |
| 27 | `27-instance-terminate-and-runaway-guard.md` | Instance TERMINATE + recursive/loop guardrail | 📝 kutилmoqда |

\* Reja 21: `JobDispatcher` (type routing) `JobQueueAdapters`да `@Primary` orqали allaqачон bor ko'ринади;
`ProcessEngineService.start`нинг async holати va `StartProcessJobHandler` mavjудлиги **koddan tasdiqлансин**.

## Belgilar
✅ bajarилган · 🔧 qisman/tekshirилсин · 📝 kutилмоqда · 📄 qaror/vizyon hujjати

## Tavsiya tartиб (davom ettirish uchun)
> Persistence/model qatlami (24/25/26) allaqачон bajarилган — endi engine mantig'и va ishonchлилик qолди.
1. **Engine to'g'риlиги:** 22 (gateway shartсиз flow) — ko'p real sxемага ta'sир qилади, **yuqori ustuvorлик**.
2. **Model integratsiyasи:** 23 (token_state + listener trace) — DDL bor, engine bu jadvалларni to'лдиришi tekshiрилsин.
3. **Tasdiqlash:** 21 (async start parity + `StartProcessJobHandler` koддан), 16 (EAV typing), 19 (execution_log to'лиқлиги).
4. **Ishonchлилик/boshqaruv:** 27 (terminate + guardrail).
5. **Multi-tenancy:** request-scoped tenant konteksti (header/JWT) — `JpaPersistenceAdapter` hozir seed `t-demo`ga bog'liq.

## Keyingi mumkin bo'lган rejалар (hali yozilmаган)
- Forms/validation moduli (start-forma + `businessProcessKeyVar` parity — reja 21 §2.1 to'liq yoqиш).
- Timer/message/boundary event to'liq implementatsiyasи (`event_subscription` + poller).
- Outbox poller (async ishonchлилик — PENDING job qayta publish).
- Multi-instance parity, external task API, migration API.
- Catalog/validate API (vizual-modeler), shadow-run parity (yangи ≡ eski engine).
