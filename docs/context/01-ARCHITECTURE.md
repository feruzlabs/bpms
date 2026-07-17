# 01 — ARXITEKTURA

## Uslub: Hexagonal (Ports & Adapters) + SPI
- **Domen/engine** markazда, tashqi dunyo (DB, RabbitMQ, HTTP, connectorlar) **port** interfeyslар orqali
  ulanadi; har port uchun **adapter** implementatsiyasi.
- **SPI (Service Provider Interface)** — connectorlар va boshqa kengaytmalар plagin sifatida ulanadi
  (masalan creditConveyer v6–v9). Engine SPI kontraktига tayanadi, konkret connectorни bilmaydi.
- Foyda: engine'ni DB/queue/connector'дан mustaqil test qilса bo'ladi; adapter almashса, engine o'zgармайди.

## Modullар va vazifasi
| Modul | Vazifa |
|---|---|
| `bpms-core` | Umumiy domen tiplari, model (ProcessDefinition, Node'lар, SequenceFlow), yordamchilar |
| `spi` | Port interfeyslар (`JobQueuePort`, `ExecutionLogPort`, connector SPI, repozitoriy portlар) va RuntimeModels |
| `expression` | Ifoda/shart hisoblash (gateway condition, listener expression) — `expressions.evaluateLogic(...)` |
| `parser-camunda` | Eski Camunda dialektини o'qiydigan parser (CamundaCompatParser) — `.bpmn` XML → ProcessDefinition |
| `engine` | **ExecutionEngine** — token'ни node'дан node'ga suradi; gateway, wait-state, complete mantig'i |
| `persistence-jpa` | Port'lарнинг DB adapterlari (JPA/SQL), repozitoriylар, Liquibase migratsiya |
| `queue-rabbit` | `JobQueuePort` RabbitMQ adapteri + `@RabbitListener` consumer (`JobQueueAdapters`) |
| `server` | Spring Boot ilova, REST kontrollerlар, konfiguratsiya, `ProcessEngineService` |
| `connectors-creditconveyer` | Domen connectorlari (creditConveyer v6/v7/v8/v9) SPI orqali |

## Runtime komponentlar (asosiy)
| Komponent | Vazifa | Asosiy jadval(lar) |
|---|---|---|
| **DefinitionRegistry** | Deploy qilingan modelни XML'дан bir marta parse qilib, **xotirада cache** qiladi (runtime re-parse yo'q) | `process_definition` (bpmn_xml = canonical) |
| **ExecutionEngine (Token Engine)** | Token'ни suradi: node → node, gateway qарори, wait-state | `execution_token`, `execution_token_state` |
| **ProcessEngineService** | Yuqori sathли orkestратsiya: deploy, start, complete, terminate | instance/token/variable/job |
| **Job Poller** (`@Scheduled`) | `PENDING` + `run_at <= now()` job'larни topadi (async ishonchlilik) | `job` |
| **Job Executor / Consumer** | Job'ни bajaradi (RabbitMQ `@RabbitListener` yoki in-process) | `job`, `dead_letter_job` |
| **JobDispatcher** | Job'ни **type bo'yicha** to'g'ri handler'ga yo'naltiradi (`SERVICE_TASK`, `PROCESS_START`) | `job` |
| **Event Handler** | Timer/message/signal obunаларини correlate qiladi | `event_subscription` |
| **External Task API** | lock → fetch → complete (mikroservis worker) | `external_task` |
| **Listener Runner** | Node'да BEFORE/AFTER execute listenerlар | `execution_listener_log` |
| **Incident Manager** | Xatolikда incident ochadi, resolve qiladi | `incident` |
| **Stats Aggregator** (`@Scheduled`) | Kunlik throughput/SLA | `process_stats_daily` |
| **Auth Guard** | Kim nima qila oladi (START/CLAIM/...) | `process_authorization` |

## Job routing (JobDispatcher)
Bitta `JobHandler` bean (`@Primary`) — `job.type()` bo'yicha yo'naltiradi. Har handler `TypedJobHandler`
(`type()` qaytaradi): `ServiceTaskJobHandler` = `"SERVICE_TASK"`, `StartProcessJobHandler` = `"PROCESS_START"`.
`JobQueueAdapters` handler sifatida shu dispatcher'ни oladi (ambiguity yo'q).

## Sync vs Async rejim (bitta kod, ikki xatti-harakat)
`bpms.job-queue` property:
- `in-process` (default) — `enqueue(job)` handler'ни **shu yerда sinxron** chaqiradi → `engine.run` inline →
  darhol COMPLETED. Test/IDE uchun; mavjud smoke-testlar yashil qoladi.
- `rabbit` — publish → `RabbitJobListener` consume → handler. **Async**: START `202 Accepted` darhol qaytади,
  consumer engine'ни yuritadi.
> `JobQueueAdapters`да RabbitMQ converter application `ObjectMapper`ни ishlatadi (JavaTimeModule → `Instant`
> serializatsiya ishlaydi) va trusted packages = `*` (JobRecord deserializatsiyasi consumer tomonда ochiladi).
> Aks holда joblар "async siнган" bo'lиб ishlамайди.

## Virtual Threads
Java 21 VT — ko'p bloklovchi I/O (connector chaqiruvlар, DB) arzon threadlарда, thread-pool tuning'siz
konkurensiya. Engine `run()` va connector chaqiruvlар VT'да ishlashи mumkin.

## Batafsil
- Ijro oqimi: `03-ENGINE-EXECUTION.md`
- Data model: `02-DATA-MODEL.md`
