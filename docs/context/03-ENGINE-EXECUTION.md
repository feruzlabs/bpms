# 03 — ENGINE IJRO OQIMI

## Umumiy lifecycle
```
deploy(bpmn_xml)                       -> process_definition + DefinitionRegistry cache
start(ref, businessKey, input)         -> process_instance + token(start) + PROCESS_START job (async)
consumer/run(model, token, bk)         -> token'ni node'dan node'ga suradi
  node = task/gateway/event/...        -> ijro yoki wait-state
wait-state (userTask/serviceTask job/  -> token WAITING/WAITING_JOB, tashqi hodisa kutadi
  timer/message/callActivity)
complete/continue                      -> token oldinga; end-event -> token COMPLETED
hamma token COMPLETED                  -> process_instance COMPLETED
```

## 1. Deploy
`bpmn_xml` → `parser-camunda` (CamundaCompatParser) → `ProcessDefinition` model → **DefinitionRegistry** cache'га.
`process_definition` qatorида **faqat XML** (canonical) saqlanади; parse qilinган model xotirада, runtime
re-parse **yo'q** (reja 17).

## 2. Start (async — reja 21)
`POST /api/v1/process-instances`:
1. `process_instance` (RUNNING) + boshlang'ich `token_variable` (EAV) + start `execution_token` (ACTIVE) — DB.
2. `INSTANCE_START` `execution_log`.
3. `PROCESS_START` `job` (PENDING) — **avval `jobs.save` (DB outbox), keyin `jobQueue.enqueue` (RabbitMQ)**.
4. Darhol **202 (RUNNING)** qaytади (engine request thread'да ishlамайди).
5. Consumer (`StartProcessJobHandler`) engine'ни yuritadi (`engine.run(...)`).
> Parity (eski `createInstanceToken`): start-event **forma** bo'lsa → `validateStartForm(model, input)`; business
> key start-forma `businessProcessKeyVar`'дан (aks holда request bk). Forma moduli hali yo'q bo'lsa — skip, lekin
> `resolveBusinessKey` qo'shiladi.
> **Rejim:** `in-process` — start sinxron (COMPLETED darhol); `rabbit` — async (202, keyin GET bilan kuzatilади).

## 3. Token ijrosi — `ExecutionEngine.run()`
Token joriy node'да; `run()` uni ketma-ket suradi:
- Node ijrosi atrofида (reja 23): `execution_token_state` `ACTIVE` INSERT → **BEFORE_EXECUTE** listenerlар
  (`execution_listener_log`) → asosiy node logikasi → **AFTER_EXECUTE** listenerlар → step `COMPLETED/FAILED`.
- Listener xato → node **FAILED** (jim yutilmайди, propagate).

### Node turlari
| Node | Xatti-harakat |
|---|---|
| startEvent | boshlang'ich token; oldinga |
| endEvent | token `COMPLETED`; hamма token tugаса instance COMPLETED |
| serviceTask | connector chaqiruvi → odatда `SERVICE_TASK` **job** (async); token `WAITING_JOB` |
| userTask | `user_task` yaratilади; token `WAITING`; `POST /tasks/{id}/complete` bilан davom |
| exclusiveGateway | 1 ta yo'l tanlanади (pastга qаранг) |
| inclusiveGateway | hamма true shart olinади; hech biri bo'lmаса default/shartsиз |
| parallelGateway | split/join (token'lар bo'linади/birlashади) |
| callActivity / subprocess | bola instance/token; `parent_instance_id`/`parent_token_id` |
| timer/message/signal event | `event_subscription`; token kutади |

### Gateway shart mantig'i (reja 22 — MUHIM tuzatish)
Exclusive (va inclusive) gateway'да **shartsiz** chиqувчи flow **implicit default** sifatida olinади:
1. avval **shartли** tarmoqlар (condition true) — birinchиси;
2. hech biri mos kelmаса → **explicit `default`** YOKI **shartsиз** flow;
3. hech nima → haqiqий o'lик yo'l.
> Buнgacha shartsиз (merge) gateway o'lик yo'l bo'lиб, token gateway'да "COMPLETED" bo'lиб qолаётган edi.
> Eski parity: `FlowService.isAcceptedCondition` — `condition == null` → true.

## 4. Service task → job → connector
serviceTask → `SERVICE_TASK` job (PENDING) → RabbitMQ → `ServiceTaskJobHandler` → connector (SPI,
creditConveyer v6–v9) chaqirилади → natija `token_variable`ga → token oldinga. Connector start/end/error
`execution_log`ga yozilади.

## 5. Wait-state'lар va davom etish
- **userTask:** `user_task.completed=true` (+ `submitted_data`) → token davom.
- **serviceTask job:** consumer job'ни bajарgach token davom.
- **timer:** `event_subscription`(TIMER) yoki `job`(TIMER, `run_at`) → vaqti kelганда poller/consumer davom ettiradi.
- **message/signal:** tashqi correlate (`business_key + event_name`) → token uyg'onади.

## 6. O'zgaruvchilар (EAV + typing)
`token_variable`: `type` (STRING/LONG/DOUBLE/BOOLEAN/DATE/JSON/OBJECT/FILE), qiymat `value_text`/`value_json`.
> ⚠️ EAV typing bug tarixi (reja 16): decimal→long crash — typing to'g'ри map qilинишини tekshiring.
UPDATE → trigger `token_variable_history`ga eski qiymат + `revision++`.

## 7. To'xtatish (reja 27)
- **SUSPEND** (qайтариладиган) / **TERMINATE** (mutlaq). TERMINATE: instance TERMINATED + token CANCELED +
  job CANCEL + `event_subscription` DELETE + user_task/incident yopilади + audit.
- **Kooperativ:** consumer TERMINATED instance job'ини ack-drop; engine `run()` har transition'да
  termination-flag + **`MAX_STEPS_PER_RUN` step-budjet** tekshiради (sinxron cheksиз loop kafolат bilan uzилади).
- **Guardrail:** node qайта-tashrif chegарasи, subprocess depth cap, spawn cap → incident + SUSPEND.
- Cascade terminate: root + avlод (recursive CTE).

## 8. Diagnostika ("nega bunday natija?")
1. `execution_token_state` — token qayси yo'lни, qачон, qандай statusда bosди.
2. `execution_log` — connector inputs/outputs/xato, gateway qайси flowни tanlaди.
3. `execution_listener_log` — shu qадамда listenerlар qандай ишлади.
4. `incident` — ochiq xatoliklар.
