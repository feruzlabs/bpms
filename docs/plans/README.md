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
| 21 | `21-async-process-start.md` | START'ni async qilish (DB → RabbitMQ → consumer) | 📝 kutilmoqda |

## Tavsiya yo'l xaritasi (roadmap)
1. **Arxitektura:** 11 → 12/13 (vizyon) → 14 (compat).
2. **Ishga tushirish:** 15 (docker/persistence/deploy/execute/start) → 16 (EAV fix) → 17 (cache) → 19 (log).
3. **Domen:** 18 → 20 (v6/v7/v8/v9 connectorlar) — real protseslarni sinash.
4. **Async/ishlab chiqarish:** 21 (async start) → (keyingi: outbox poller, timer/message event, catalog/validate API, shadow-run parity).

## Test sxemalari (compat-corpus)
- `TUNE_CREDIT_REQUEST_4888` — V6/V7 · `TUNE_CREDIT_REQUEST_6004` — V8 · `TUNE_CREDIT_REQUEST_7000` — V8+V9.
- Demo: `docs/examples/demo-credit-scoring.bpmn`, `credit-v9-smoke.bpmn`.

## Keyingi mumkin bo'lgan rejalar (hali yozilmagan)
- Outbox poller (async ishonchlilik), timer/message/boundary event, multi-instance parity, catalog/validate API
  (vizual-modeler), shadow-run parity (yangi ≡ eski engine), boshqa versiya (v2/v3/v5) connectorlari.
