# bpms-new-backend

New BPMN engine (Java 21 / Spring Boot **3.5.14** / Virtual Threads). Side-by-side with
`liveScoring-bpms-backend` (READ-ONLY during this work).

Plans: `../../plans/14-bpms-schema-compatibility.md`, `../../plans/15-bpms-new-engine-runnable.md`.

## Modules

| Module | Role |
|---|---|
| `bpms-core` | Sealed domain model + `ParseResult` / `CompatWarning` |
| `bpms-spi` | Parser / Connector / ports / `ScriptNamespaceProvider` |
| `bpms-expression` | SpEL + `isExprStr` (old-engine parity) |
| `bpms-parser-camunda` | `CamundaCompatParser` |
| `bpms-engine` | Token walker + type-safe `ConnectorRegistry` |
| `bpms-persistence-jpa` | Postgres/Flyway + EAV `VariableStorePort` |
| `bpms-queue-rabbit` | `JobQueuePort` (in-process \| rabbit `bpms.new.jobs`) |
| `bpms-server` | Boot app (port **8090**) |

## Build

```bash
export JAVA_HOME=.../jdk-21
mvn -q verify
```

## Docker smoke (plan 15 §9)

Isolated infra: Postgres **5433**, RabbitMQ **5673/15673**, app **8090**.

```bash
docker compose up --build
# wait until app healthy (host port 8090; if busy map "8091:8090" in compose)

curl -s http://localhost:8090/actuator/health

curl -s -X POST http://localhost:8090/api/v1/process-definitions \
  -H "Content-Type: application/xml" \
  --data-binary @compat-corpus/demo/demo-linear.bpmn

curl -s -X POST http://localhost:8090/api/v1/process-instances \
  -H "Content-Type: application/json" \
  -d '{"definitionKey":"demo-linear","businessKey":"T1","variables":{"amount":150}}'
# → status COMPLETED, token on end_ok

curl -s http://localhost:8090/api/v1/process-instances/{instanceId}
```

Default `BPMS_JOB_QUEUE=in-process` (sync connectors). For async Rabbit consumer path:

```bash
BPMS_JOB_QUEUE=rabbit BPMS_RABBIT_LISTENER_ENABLED=true docker compose up --build
# start may return RUNNING/WAITING_JOB; poll GET until COMPLETED
```

## API

- Swagger UI: http://localhost:8090/swagger-ui.html
- `POST /api/v1/schema/parse`
- `POST /api/v1/process-definitions` (422 if CompatWarning)
- `POST /api/v1/process-instances` / `GET /{id}`
- `POST /api/v1/tasks/{id}/complete`

## Notes

- Demo connectors: `noop`, `echo` (registry is type-safe `Map`; duplicate id fails at startup).
- Nested form Variant A/B and external HR schemas remain Phase-0 open (see `PHASE0-INVENTORY.md`).
