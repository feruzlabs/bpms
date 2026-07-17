# BPMS New Backend — kontekst hujjatlari (docs/context)

Bu papka (`docs/context/`) — `bpms-new-backend` loyihасини **davom ettirish uchun onboarding/kontekst
hujjatlари**. Yangi chat/sessiya avval `00-PROJECT-CONTEXT.md`ни o'qishi kerak.

> **Sxема source of truth `docs/` ildizidа** (bu papkадан bir yuqори): mavjуd `../README.md` (jadval indeksi +
> FK/ON DELETE), `../FEATURES.md` (biznes-feature A–L), `../bpms-schema.dbml` (ERD). Bu papка ularни
> **takrorламайди** — umumий kontekst, arxitektura, ijро oqimи va roadmapни beради.

## Tartib bilan o'qish
| # | Fayl | Nima haqida |
|---|---|---|
| 00 | [`00-PROJECT-CONTEXT.md`](./00-PROJECT-CONTEXT.md) | **Avval shuni o'qi** — loyiha nima, oltin qoida, tech stack, holat |
| 01 | [`01-ARCHITECTURE.md`](./01-ARCHITECTURE.md) | Hexagonal tuzilma, modullар, runtime komponentlar, sync/async rejim |
| 02 | [`02-DATA-MODEL.md`](./02-DATA-MODEL.md) | 25 jadval, vazifalari, bog'liqliklar, migratsiya vositasi |
| 03 | [`03-ENGINE-EXECUTION.md`](./03-ENGINE-EXECUTION.md) | Deploy → start → token ijrosi → gateway → job → complete; wait-state'lар |
| 04 | [`04-ROADMAP-PLANS.md`](./04-ROADMAP-PLANS.md) | Rejalар 11–27 indeksi, holati, keyingi ishlar |
| 05 | [`05-CONVENTIONS.md`](./05-CONVENTIONS.md) | Oltin qoidalar, kodlash konvensiyalari, migratsiya/FK/CHECK siyosati, workflow |
| 06 | [`06-GLOSSARY.md`](./06-GLOSSARY.md) | Domen va engine atamalари lug'ati |

## Qo'shimcha manbalar (repo ichida)
- `../README.md` — v3 sxema jadval indeksi + FK/ON DELETE + partition/trigger (**mavjud, source of truth**).
- `../FEATURES.md` — sxema qaysi biznes-feature'larni yoqadi (A–L) — reja 26 natijasi.
- `../bpms-schema.dbml` — v3 DB sxemasi (dbdiagram.io).
- `../writing-connectors.md` — connector yozish qo'llanmasi.
- Batafsil reja fayllar (11–27) — `plans/bpms-new-backend/` (repo ildizida).

> Eslatma: bu hujjatlar suhbat kontekstiga asoslangan. Aniq class nom, API path va holatlarни har doim
> **koddan tekshiring** — "taxminiy" deб belgilangan joylар shu sababли.
