# bpms-new-backend

New BPMN engine (Java 21 / Spring Boot **3.5.14** / Virtual Threads). Side-by-side with
`liveScoring-bpms-backend` (READ-ONLY during this work).

Authoritative plan: `../../plans/14-bpms-schema-compatibility.md`.

## Modules

| Module | Role |
|---|---|
| `bpms-core` | Sealed domain model + `ParseResult` / `CompatWarning` |
| `bpms-spi` | Parser / Connector / `ScriptNamespaceProvider` SPIs |
| `bpms-expression` | SpEL + `isExprStr` (old-engine parity) |
| `bpms-parser-camunda` | `CamundaCompatParser` |
| `bpms-server` | Boot app (port **8090**) |

## Build / test

```bash
export JAVA_HOME=.../jdk-21
mvn -q verify
```

## Phase status (plan 14 §6)

| Phase | Status |
|---|---|
| 0 Inventory | Partial — see `PHASE0-INVENTORY.md` |
| 1 Compat corpus | credit-conveyor copied; external empty |
| 2 CamundaCompatParser | Implemented + fixture/corpus tests |
| 3 Expression evaluator | SpEL + heuristic + SPI namespaces |
| 4–6 Execution / shadow / rollout | Not started |

## Swagger / OpenAPI

- UI: http://localhost:8090/swagger-ui.html
- Spec: http://localhost:8090/v3/api-docs

## API (early)

`POST /api/v1/schema/parse` — `Content-Type: application/xml` → node/flow counts + warnings.