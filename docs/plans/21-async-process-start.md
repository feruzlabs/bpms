# Task 21 — Protses START'ini async qilish (DB → RabbitMQ → consumer)

> **Maqsad:** `POST /api/v1/process-instances` **sinxron** ijro qilmasin. Talab: request kelganda → **DB'ga yoz**
> (instance + variables + start token + job) → **RabbitMQ**'ga tashla → **darhol 202 qaytar** → **consumer** engine'ni
> yuritadi.
> **Ish papkasi:** `bpms/bpms-new-backend/`. Eski bpms READ-ONLY.

## 0. Hozirgi holat (grounding)
- `ProcessEngineService.start(...)` — instance/variables/token saqlaydi, keyin **`engine.run(...)` request thread'da**
  (sinxron) → COMPLETED qaytaradi. Aynan shuni async qilamiz.
- Async infra bor: `JobQueuePort.enqueue(job)` + `JobHandler`. `bpms.job-queue`: `in-process` (default — handler'ni
  **sinxron** shu yerda chaqiradi) yoki `rabbit` (publish → `RabbitJobListener` consume → handler).
- **Faqat `SERVICE_TASK` job'lari bor, bitta handler** (`ServiceTaskJobHandler`). START job yo'q.
- ⚠️ **Bug/bloker:** `JobQueueAdapters` handler'ni `handlers.getObject()` (yagona) bilan oladi — **ikkinchi handler
  qo'shilsa `getObject()` xato beradi** (ambiguous). Shuning uchun avval **type bo'yicha routing** kerak.

## 1. Job routing (type bo'yicha) — MAJBURIY birinchi qadam
`JobDispatcher` (bitta `JobHandler` bean) — `job.type()` bo'yicha yo'naltiradi:
```java
public interface TypedJobHandler extends JobQueuePort.JobHandler { String type(); }   // "SERVICE_TASK" | "PROCESS_START"

@Component
class JobDispatcher implements JobQueuePort.JobHandler {
    private final Map<String, TypedJobHandler> byType;
    JobDispatcher(List<TypedJobHandler> handlers) {
        this.byType = handlers.stream().collect(toMap(TypedJobHandler::type, h -> h));
    }
    public void handle(JobRecord job) {
        TypedJobHandler h = byType.get(job.type());
        if (h == null) throw new IllegalStateException("No handler for job type: " + job.type());
        h.handle(job);
    }
}
```
- `JobQueueAdapters`da `ObjectProvider<JobHandler>` → **`JobDispatcher`** (bitta bean) inject qilinadi (`getObject()` endi
  aniq bitta bean — `JobDispatcher`).
- `ServiceTaskJobHandler` → `implements TypedJobHandler`, `type() = "SERVICE_TASK"`.

## 2. START'ni async qilish (`ProcessEngineService.start`)
`engine.run(...)` **o'rniga** PROCESS_START job'ini navbatga sol:
```java
public InstanceView start(String ref, String businessKey, Map<String,Object> input) {
    DefinitionRecord d = ...resolve...;
    String iid = UUID.randomUUID().toString();
    instances.save(new InstanceRecord(iid, d.id(), businessKey, InstanceStatus.RUNNING, clock.now(), null)); // DB
    variables.putAll(iid, input == null ? Map.of() : input);                                                 // DB
    execLog.log(INSTANCE_START ...);                                                                          // DB
    ProcessDefinition model = registry.get(d.id());
    StartEventNode start = ...;
    TokenRecord token = new TokenRecord(UUID.randomUUID().toString(), iid, start.id(), TokenStatus.ACTIVE, null);
    tokens.save(token);                                                                                       // DB

    // engine.run(...) YO'Q — o'rniga PROCESS_START job:
    String payload = json.writeValueAsString(Map.of(
        "instanceId", iid, "tokenId", token.id(), "businessKey", businessKey == null ? "" : businessKey));
    JobRecord job = new JobRecord(UUID.randomUUID().toString(), iid, token.id(),
        "PROCESS_START", payload, JobStatus.PENDING, 0, clock.now());
    jobs.save(job);            // 1) DB'ga yoz (outbox)
    jobQueue.enqueue(job);     // 2) RabbitMQ'ga tashla
    return getInstance(iid);   // 3) darhol qaytar (RUNNING, token start'da)
}
```
> **Tartib:** avval `jobs.save` (DB), keyin `jobQueue.enqueue` (Rabbit) — aynan talab qilingan "DB → RabbitMQ".

## 2.1 Eski `createInstanceToken(Process, HashMap<String,Object> values)` mantig'i — PARITY (qo'shildi)
> Manba: `BpmExecutionService.createInstanceToken(Process, HashMap)` (eski engine start semantikasi). Yangi start shu
> mantiqni takrorlashi kerak — oldingi plan 21'da **yo'q edi**.

Eski oqim (kod o'qib):
1. **`validateStartFormSubmit(process, values)`** — agar **start-event forma** bo'lsa, kelgan `values` shu formaga
   qarab validatsiya qilinadi (`ValidationService`). Validatsiya xato → instance yaratilmaydi.
2. **Business key start-formadan:** forma `businessProcessKeyVar`ga ega bo'lsa → `businessKey = values[businessProcessKeyVar]`
   (alohida param emas); yo'q bo'lsa → request'dagi `businessKey`.
3. `createInstanceTokenGetState(process[, bk])` — deployed tekshiruvi → instance + start token.
4. **`setValues(state, values)`** — boshlang'ich o'zgaruvchilar (EAV).
5. **`deploy(state)`** — token'ni oldinga suradi, wait-state aniqlaydi (userTask/callActivity/gateway/subprocess/event →
   `waitingFor...`). Bu — yangi engine `run()` ekvivalenti.

Yangi start'ga qo'shiladi (§2 dagi enqueue'dan **oldin**):
```java
validateStartForm(model, input);                        // 1) start-event forma bo'yicha; xato -> HTTP 422
String bk = resolveBusinessKey(model, input, businessKey); // 2) forma businessKeyVar bo'lsa undan, aks holda request bk
// 3) instance + variables (DB) → 4) PROCESS_START enqueue (§2) → consumer run() = deploy() ekvivalenti
```
> **Muhim (real sxemalar):** `4888/6004/7000` curl'larida `*_tune_credit_request_start_form` o'zgaruvchilari bor —
> demak bu protseslarda **start-event forma bor**, va `businessProcessKeyVar` ehtimol `request_id_..._start_form`
> (4888 curl'da `businessKey == request_id...start_form` qiymati). Ya'ni bu parity **real ishlaydi**, dekorativ emas.
>
> **Bog'liqlik:** start-forma validatsiyasi + `businessProcessKeyVar` yangi engine'da **forma/validatsiya moduli**ni
> talab qiladi (plan 14 forms). Bosqichli: (a) forma **yo'q** protseslarda bu qadamlar **skip** (async mexanizmi §2
> darhol ishlaydi); (b) forma **bor** protseslarda modul qo'shilgach validatsiya+businessKeyVar yoqiladi. Agar forma
> moduli hali yo'q bo'lsa — kamida `resolveBusinessKey` (businessKeyVar'dan bk olish) qo'shilsin, validatsiyani keyin.

## 3. `StartProcessJobHandler` (consumer bajaradi)
```java
@Component
class StartProcessJobHandler implements TypedJobHandler {
    public String type() { return "PROCESS_START"; }
    @Transactional
    public void handle(JobRecord job) {
        JobRecord cur = jobs.findJobById(job.id()).orElse(job);
        if (cur.status() == JobStatus.COMPLETED) return;                 // idempotent
        jobs.save(running(cur));
        var payload = json.readValue(cur.payload(), MAP);
        String iid = (String) payload.get("instanceId");
        String bk  = (String) payload.getOrDefault("businessKey", "");
        InstanceRecord inst = instances.findInstanceById(iid).orElseThrow();
        ProcessDefinition model = registry.get(inst.definitionId());
        TokenRecord token = tokens.findTokenById((String) payload.get("tokenId")).orElseThrow();
        engine.getObject().run(model, token, bk);                        // ASOSIY ijro shu yerda
        jobs.save(completed(cur));
    }
    // xato -> FAILED + retry (ServiceTaskJobHandler kabi)
}
```

## 4. Rejim (config)
- **Async (talab):** `bpms.job-queue=rabbit` → `start()` enqueue publish qiladi, request darhol qaytadi, `RabbitJobListener`
  consumer engine'ni yuritadi. (docker-compose'da RabbitMQ bor — Faza G.)
- **Sinxron (test/IDE):** `bpms.job-queue=in-process` (default) → `enqueue` handler'ni **shu yerda** chaqiradi →
  `engine.run` inline → COMPLETED qaytadi. **Mavjud smoke/testlar buzilmaydi** (sinxron qoladi).
> Ya'ni bitta kod — rabbit'da async, in-process'da sync. Alohida flag shart emas.

## 5. HTTP status
Async (rabbit) rejimda `POST` → **202 Accepted** + `{id, status: "RUNNING", tokens:[start]}`. Foydalanuvchi
`GET /api/v1/process-instances/{id}` (va `.../logs`) bilan natijani kuzatadi.

## 6. Ishonchlilik (outbox — tavsiya)
`jobs.save(PENDING)` DB'ga tushib, `enqueue` publish'idan oldin ilova o'lsa — job DB'da qoladi lekin Rabbit'ga
ketmaydi. **Poller** (`@Scheduled`) `PENDING` + eski `run_at` job'larni topib qayta publish qiladi (idempotent consumer).
Bu — klassik transactional-outbox; hozir minimal (save→enqueue) yetadi, poller keyin qo'shilsa bo'ladi.

## 7. DoD
- [ ] `JobDispatcher` (type bo'yicha) — `getObject()` ambiguity yo'q; `ServiceTaskJobHandler` = TypedJobHandler("SERVICE_TASK").
- [ ] **`createInstanceToken` parity (§2.1):** start-forma validatsiyasi (bor bo'lsa) + business key start-forma `businessProcessKeyVar`dan (aks holda request bk) — enqueue'dan oldin. Forma yo'q → skip.
- [ ] `start()` `engine.run` chaqirmaydi — DB (instance/vars/token/job) → `jobQueue.enqueue(PROCESS_START)` → darhol qaytadi.
- [ ] `StartProcessJobHandler` — consumer engine'ni yuritadi; idempotent; xato→FAILED/retry.
- [ ] `bpms.job-queue=rabbit`: POST 202 (RUNNING) darhol; consumer bajaradi; `GET /{id}` keyin COMPLETED.
- [ ] `bpms.job-queue=in-process`: sinxron (mavjud testlar yashil).
- [ ] `TUNE_CREDIT_REQUEST_4888` (foydalanuvchi curl'i) rabbit rejimida: POST darhol RUNNING; DB'da job(PROCESS_START) +
      instance + variables; consumerdan keyin token-yo'li + logs.
- [ ] Eski bpms 0 diff.

## 8. Cursor'ga topshiriq (namuna)
```
Yangi task. Ish papkasi: @bpms-new-backend. @21-async-process-start.md ni bajar.
1) JobDispatcher (type bo'yicha routing) + ServiceTaskJobHandler=TypedJobHandler.
2) ProcessEngineService.start: engine.run o'rniga PROCESS_START job (jobs.save -> jobQueue.enqueue), darhol qaytar.
3) StartProcessJobHandler (consumer engine.run yuritadi, idempotent).
4) rabbit rejimida POST 202 RUNNING; in-process sinxron qoladi (testlar buzilmasin).
Eski bpms READ-ONLY. Avval fayllarni o'qib, planni ayt, keyin yoz.
```
