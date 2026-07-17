# 05 — KONVENSIYALAR va QOIDALAR

## Oltin qoida (buzilmasin)
1. **Kod faqat `bpms/bpms-new-backend/`da.**
2. **Eski `bpms/liveScoring-bpms-backend/` = READ-ONLY** — faqat parity/dialekt uchun o'qilади; hech qачон
   o'zgартирилмайди. Har commit'да "**eski bpms 0 diff**".
3. Guardrail fayllар: `rcc/.cursor/rules/bpms-new-engine.mdc`, `rcc/.cursorrules` — buzилмаsин.
4. **Infra izolyatsiya:** yangi bpms o'z Postgres (`bpms_new`:5433) + RabbitMQ (:5673); eski bpms infra'sига
   **ULANMАSЛИК**.

## Compat (parity) tamoyili
Yangi parser eski **Camunda dialektини** aynан takrorлайди → eski `.bpmn` **o'zгарисsиз** yuklanади. Yangi
xatti-harakат yozишдан oldин eski engine (`FlowService`, `BpmExecutionService` va h.k.) qандай qилганини
**o'qиб**, o'shанга parity qилинади. Misоllар: gateway `condition == null` → true (reja 22); start
`createInstanceToken` semantикасi (reja 21 §2.1).

## Migratsiya (IMPLEMENT QILINGAN holat)
- **Vosita: Liquibase XML** (Flyway'дан ko'chирилди — reja 24). Root: `bpms-persistence-jpa/src/main/resources/
  db/changelog/db.changelog-master.xml`, ичида `changes/001-core.xml … 004-auth-stats.xml`.
- Postgres-spetsифик DDL (PARTITION, plpgsql trigger, CHECK, array) — `<sql>` **CDATA** ичида.
- Tracking: `databasechangelog` / `databasechangeloglock` (avtomatик).
> Eslatма: reja 24/25 matnida "SQL-formatted changelog" taклиф qилинган edi; amaliyotда **XML** varianti
> tanlanган (Postgres DDL'ни CDATA ичида ushlash qulай). Yangi changeset — shu XML uslубда qo'шилsин.

## DDL siyosati (reja 25)
- **FK ON DELETE:** runtime-bola → CASCADE; katalog → RESTRICT; diagnostik → SET NULL (to'liq jadval: reja 25 §1).
- **CHECK:** har enum ustунга; **`node_type`/`event_type` bundan mustasно** (ochiq ro'yxат — brittle migratsiядан qochish).
- **Circular FK** (`execution_token↔incident`): ustун avval FKsиз, keyin `ALTER ADD`.
- **Partitioning** (`execution_log`, `token_variable_history`): `PARTITION BY RANGE`, PK partition kalitини
  o'z ичига олади, oylик partitsiя + `DEFAULT`.
- `gen_random_uuid()` uchun `CREATE EXTENSION IF NOT EXISTS pgcrypto`.

## Runtime holат modeli (nomlаш)
- `execution_token` — **UPSERT** (joriy holат). `execution_token_state` — **insert-then-update** (har tashrif
  bitta qатор, `sequence_no` bilan). "History" emas — "**state**" (reja 23).
- Job type: `SERVICE_TASK | PROCESS_START | TIMER | MESSAGE | ASYNC_JOB`. Handler'lар `TypedJobHandler`,
  `JobDispatcher` type bo'yicha yo'naltiради.

## Async konfiguratsiya
- `bpms.job-queue=in-process` (default) — sinxron, test/IDE; mavjуd testlar yashил qолsин.
- `bpms.job-queue=rabbit` — async; POST 202 (RUNNING) darhol, consumer yuritади.
- RabbitMQ converter application `ObjectMapper`ни ishlатsин (Instant), trusted packages = `*`.

## Test kutилmаları
- Har reja **DoD checklist** bilan; test yozилsин (unit + real sxема: 4888/6004/7000).
- Regres: normal instance oxиригача COMPLETED; guardrail'lар normal oqимга xalал bermаsin.
- "Eski bpms 0 diff" har PR'да.

## Ish workflow (rejа → ijro)
1. Reja `.md` yozилади (self-contained, muammо + tuzatish + test + DoD + Cursor/Claude Code topshiриq).
2. Terminal Claude Code yoki Cursor ijро qилади: **avval fayllарни o'qиб, rejаni aytsин, keyin yozsин**.
3. Natижа koddан tekshirилади, keyingi qадам.
> Eslatма: `@fayl` mention — Cursor-maxsус. Terminal Claude Code'да oddий **path** ko'рсатилsin.

## Kod usluби (umumий)
- Java 21, records/sealed interfeyslар model uchun; Hexagonal — engine port'ларга tayanади, adapter'ни bilмайди.
- Yangi jadval/port qo'шилса — `spi`да port, `persistence-jpa`да adapter, migratsiya changelog.
