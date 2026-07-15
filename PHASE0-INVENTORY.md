# Phase 0 — Verify & inventory (plan 14 §4 / §6)

> Status: **partial** — code-verified dialect is locked (§1); inventory blockers that need external input remain open.
> Date: 2026-07-15

## Locked by code read (no longer open questions)

| Fact | Source | Decision for CamundaCompatParser |
|---|---|---|
| connectorId carrier | `BpmHelper` | `<camunda:connector><camunda:connectorId>` (NOT property) |
| Events | `EventService` | Message + Timer only; signal/error → CompatWarning |
| Expression | `InstanceTokenService` | Spring SpEL + `isExprStr` + error→null→false |
| Multi-instance | `BpmHelper.getMultiInstance` | seq/parallel, cardinality/collection/elementVariable/completionCondition |
| Form properties | `BpmHelper.getPropertiesField` | **flat** `id → String` (Camunda XML value attribute). Nested JSON, if any, is encoded **inside that string** by the writer — parser stores the string verbatim (same as old engine). |

## Open blockers (need answers — do not guess)

### 1. Nested-property persistence (Variant A/B) — form designer / writing path

- **Parser read path is already fixed:** always flat String values (mirrors old `getCamundaValue()`).
- **Still open for form-writer / round-trip parity:** whether nested `parameters: [...]` is stored as escaped JSON string (Variant A) or another mechanism (Variant B). Requires one test-deploy against old engine DB (`plans/bpms-new/02-phase0-verify-persistence.md`).
- **Impact on Phase 2 parser:** none for import. Blocks form designer / emit later.

### 2. Other-team schemas (hrms/labour)

- Local repo has only credit-conveyor `processes/*.bpmn`.
- Need ≥3 representative schemas (multi-sign, subprocess/pool, complex forms) from other teams.
- Without them: compat coverage uses credit-conveyor corpus only; §1.4 signal/error usage and §1.6 `bpms.hrms.*` call sites stay unverified against real files.

### 3. labour / Signature\*

- Out of core scope. Script namespace SPI supports domain plug-in; no `hrms`/`labour` hardcode in core.

### 4. "Plugin components" meaning (§4.2)

- Still ambiguous (element templates vs custom field types vs engine plugins vs custom XML namespaces).
- Parser covers Camunda extension elements known from old engine; unknown namespaces → CompatWarning.

## Working assumptions for Phase 1–3 (documented, not guessed)

1. Compat target for first cut = old-engine dialect ∩ credit-conveyor corpus.
2. Form field properties import = flat String map (old read path). Nested decode is deferred until Variant A/B is confirmed.
3. Script namespaces registered via SPI only.

## Next ask from user / other team

1. Run nested-property deploy test OR confirm Variant A from HR frontend.
2. Provide 3–5 other-team `.bpmn` files into `compat-corpus/external/`.
3. Clarify "plugin" meaning in one sentence.
