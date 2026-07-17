# 00 — LOYIHA KONTEKSTI (avval SHUNI o'qing)

> Bu hujjat — yangi chat/sessiya (Claude Code, Cursor va h.k.) uchun **kirish nuqtasi**. Loyihada ish boshlashдан
> oldin shuni, keyin kerakiga qarab qolgan `docs/*` fayllarни o'qing.

## Bu nima?
**BPMS (Business Process Management System) — yangi engine**, `bpms/bpms-new-backend/` ичида. U eski
mahsulot (`bpms/liveScoring-bpms-backend/`) o'rniga **yonma-yon (side-by-side)** quriladi va uni bosqichma-bosqich
almashtiradi. Domen — **bank / kredit-skoring (credit conveyor)**: ariza keladi → skoring API'lar chaqiriladi →
gateway qaror qiladi → operator user-task'да tasdiqlaydi → natija.

## Oltin qoida (GUARDRAIL — buzilmasin)
- **Kod faqat `bpms/bpms-new-backend/` ichida yoziladi.**
- **Eski `bpms/liveScoring-bpms-backend/` = READ-ONLY.** Unga faqat *parity/dialekt* (eski xatti-harakatни
  takrorlash) uchun **qaraladi**, hech qачон o'zgartirilmaydi.
- Har PR/commit'да "eski bpms 0 diff" bo'lishi shart.
- Guardrail fayllari: `rcc/.cursor/rules/bpms-new-engine.mdc` (+ `rcc/.cursorrules`).

## Texnologiya
- **Java 21** (Virtual Threads), **Spring Boot 3.5.14**.
- Arxitektura: **Hexagonal (Ports & Adapters) + SPI** (batafsil: `01-ARCHITECTURE.md`).
- **PostgreSQL** (`bpms_new`, port **5433**) + **RabbitMQ** (port **5673**) — eski bpms infra'sига
  **ULANMAYDI**, alohida izolyatsiya.
- Migratsiya: **Liquibase XML** changelog (`db/changelog/db.changelog-master.xml` + `changes/001-004.xml`) —
  batafsил `docs/05-CONVENTIONS.md`.

## Modullar (multi-module Maven/Gradle)
`bpms-core / spi / expression / parser-camunda / engine / persistence-jpa / queue-rabbit / server /
connectors-creditconveyer`
(har birининг vazifasi: `01-ARCHITECTURE.md`).

## Asosiy fikrlash modeli (bir qatorда)
- `execution_token` = **hozir qayerda**
- `execution_token_state` = **qanday yo'l bosdi** (har node-tashrif; kirganda ACTIVE insert, chiqishda update)
- `execution_log` = **nega shunday bo'ldi** (connector/gateway diagnostikasi)
- `token_variable` = **qanday ma'lumot bilan** (EAV)
- `job` = **keyingi qadam navbatда kutmoqda** (async, RabbitMQ)
- To'liq: `docs/02-DATA-MODEL.md`

## Compat tamoyili
Yangi parser eski **Camunda dialektini** takrorlaydi → eski `.bpmn` sxemalar **o'zgармasдан** yuklanadi.
Real test sxemalari (compat-corpus):
- `TUNE_CREDIT_REQUEST_4888` — V6/V7
- `TUNE_CREDIT_REQUEST_6004` — V8
- `TUNE_CREDIT_REQUEST_7000` — V8+V9
- Demo: `docs/examples/demo-credit-scoring.bpmn`, `credit-v9-smoke.bpmn`

## Hozirgi holat (qisqa — aniqni koddan tekshiring)
- Deploy / execute / start / async (RabbitMQ) — **asosan ishlaydi**.
- DefinitionRegistry cache (runtime re-parse yo'q) — **bor**.
- v6/v7/v8/v9 connectorlар import qilingan, real sxemalар sinaladi.
- **v3 DB sxема (~29 jadval) implement qilingan** — Liquibase XML changelog (`changes/001-004.xml`), trigger +
  seed sinalган. Jadval indeksi: mavjуd `docs/README.md`.
- Ochiq/rejalashtirilган ishlar: `docs/04-ROADMAP-PLANS.md` (gateway shartсiz-flow fix, token_state trace,
  async start parity, terminate+guardrail, forms/validation, timer/message event, request-scoped multi-tenancy).

## Ishни qanday olib boramiz (workflow)
Reja `.md` fayllar tayyorlanadi (`plans/bpms-new-backend/` yoki shu `docs/`) → Claude Code (terminal) yoki
Cursor ularни bajaradi. Har reja **self-contained**, DoD checklist va "eski bpms 0 diff" bilan.

## Keyingi qadam
1. `01-ARCHITECTURE.md` — tuzilma va komponentlar.
2. `03-ENGINE-EXECUTION.md` — jarayon qanday ishga tushib, boradi.
3. `04-ROADMAP-PLANS.md` — nima qilingan, nima qolgan.
