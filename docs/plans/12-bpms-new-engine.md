# Plan 12 — Yangi BPMN dvigatel (yonma-yon, Java 21 + Spring Boot 3 + Virtual Threads)

> Maqsad: **toza, optimallashtirilgan yangi BPMN dvigatel**ni noldan yozish; **yangi versiyalar (v9/v10)** unda ishlaydi.
> Eski dvigatel (v1–v8) **butunlay tegilmaydi**, o'z joyida ishlaydi. Deployment darajasidagi Strangler.
> Stek: **Java 21, Spring Boot 3.2+ (Java 21 baseline), Virtual Threads (Loom), Hexagonal (Ports & Adapters)**.
> Bu — katta, ko'p bosqichli loyiha; quyida MVP → to'liq yo'l xaritasi.

---

## 0. Asosiy prinsiplar
1. **Yonma-yon:** yangi dvigatel — **alohida deployment** (yangi modul/repo). Eski dvigatel kodiga tegilmaydi.
2. **Domen musaqil:** BPMN ijro-mantig'i framework/DB'dan **mustaqil** (Hexagonal). Ostidagi mexanizm keyin almashtirilsa ham domen o'zgarmaydi.
3. **Type-safe dispatch:** `getBean(String)` o'rniga `Map<String, Connector>` (Spring barcha connector bean'larini inject qiladi) → noto'g'ri connectorId **startup'da** bilinadi.
4. **Virtual Threads:** connector HTTP chaqiruvlari (KATM/NPS/PHP — bloklovchi I/O) virtual thread'larda → minglab parallel arzon (resurs muammosiga bevosita foyda).
5. **God-class yo'q:** token/ijro holati kichik, sinaladigan service'larga bo'linadi (eski ~1600 LOC `InstanceTokenStateService`dan farqli).
6. **O'z DB sxemasi:** yangi jadvallar (eski engine jadvallaridan ajratilgan).
7. **Parity:** yangi dvigatel eski bilan **bir xil xatti-harakat** berishi shart (shadow-run + solishtirish).

---

## 1. Dvigatel nima qilishi kerak (qamrov)
| Blok | Vazifa |
|---|---|
| **Parse** | BPMN (Camunda XML) → ichki model (elementlar, oqimlar, gateway, serviceTask, connector) |
| **Deploy** | Protsess ta'rifini saqlash/versiyalash |
| **Start** | Protsess instansiyasini boshlash (`business_key`, kirish o'zgaruvchilari) |
| **Execution** | Token-asosli graf aylanish; ketma-ketlik, gateway, expression baholash |
| **Dispatch** | serviceTask → connector (type-safe) |
| **Persistence** | Instance/token holati, o'zgaruvchilar (DB) |
| **Async** | Og'ir/tashqi qadamlar navbatda (RabbitMQ job), retry, idempotentlik |
| **Timer/Callback** | Timer-based kutish, tashqi callback |

---

## 2. Arxitektura — Hexagonal (Ports & Adapters)

```
                ┌────────────────── DOMEN (sof, frameworksiz) ──────────────────┐
                │  ProcessDefinition, Element(sealed), Gateway, ExecutionToken,  │
                │  TokenVariables, ExecutionEngine (graf aylanish + gateway)     │
                └───────────────────────────────────────────────────────────────┘
   INBOUND PORT ▲                                          ▼ OUTBOUND PORTS
   ProcessEngine (deploy/start/signal/complete)   DefinitionRepository, InstanceRepository,
        │                                          TokenRepository, ConnectorPort,
        │                                          JobQueuePort, ClockPort, EventPublisher
   ┌────┴─────┐        ┌──────────────────────────────┴───────────────────────────────┐
   │ REST      │        │  JPA/Postgres adapter │ RabbitMQ adapter │ Connector adapter   │
   │ Controller│        │  (yangi jadvallar)    │ (job queue)      │ (Map<String,Connector>)│
   └───────────┘        └───────────────────────┴──────────────────┴─────────────────────┘
```

**Paketlar:**
```
com.bpmn.v2/
├── domain/            (sof: model + ijro mantig'i, Spring'siz)
│   ├── definition/    ProcessDefinition, FlowNode(sealed), SequenceFlow, ServiceTask, Gateway...
│   ├── execution/     ExecutionToken, ExecutionState, TokenVariables, ExecutionEngine
│   └── connector/     Connector (interfeys), ConnectorContext, ConnectorResult, ConnectorDescriptor
├── port/
│   ├── in/            ProcessEnginePort (deploy/start/signal)
│   └── out/           DefinitionRepositoryPort, InstanceRepositoryPort, TokenRepositoryPort,
│                      ConnectorPort, JobQueuePort, ClockPort, EventPublisherPort
├── adapter/
│   ├── in/rest/       ProcessController (upload/deploy/start), health
│   ├── out/persistence/ JPA entity + repository impl (yangi jadvallar)
│   ├── out/queue/     RabbitMQ job adapter
│   ├── out/connector/ ConnectorRegistry (Map<String,Connector>), dispatch
│   └── parser/        BpmnParser (Camunda XML → domain)
└── config/            Spring config, Virtual Threads executor, DI
```

---

## 3. Asosiy dizayn qarorlari (kod-darajasi)

### 3.1 Connector — type-safe kontrakt (Java 21)
```java
public interface Connector {
    String id();                              // = connectorId (yoki @Component nomi)
    ConnectorResult execute(ConnectorContext ctx);
    default ConnectorDescriptor describe() { ... }   // inputlar (Tier 1 validatsiya bilan bir xil)
}
// Registry — Spring barcha Connector bean'larini inject qiladi (getBean(String) YO'Q)
@Component
class ConnectorRegistry {
    private final Map<String, Connector> byId;   // startup'da yig'iladi + validatsiya
}
```
> `ConnectorContext` — token, inputlar, o'zgaruvchilar; `ConnectorResult` — output var'lar (immutable record).
> Mavjud v9 connector'lar **adapter** orqali qayta ishlatilishi mumkin.

### 3.2 BPMN model — sealed + records (Java 21)
```java
public sealed interface FlowNode permits StartEvent, EndEvent, ServiceTaskNode, GatewayNode, ... {}
public record ServiceTaskNode(String id, String connectorId, List<ConnectorInput> inputs, ...) implements FlowNode {}
```
Element dispatch — `switch` pattern matching (`case ServiceTaskNode st -> ...`).

### 3.3 Virtual Threads (Loom) — resurs yutug'i
- Connector ijrosi bloklovchi HTTP → **virtual thread per task** executor.
- Spring Boot 3.2+: `spring.threads.virtual.enabled=true` (yoki maxsus executor) → REST + @Async + queue consumer'lar virtual thread'da.
- Natija: minglab parallel instance/connector bloklovchi chaqiruvi arzon → thread-pool tiqilishi kamayadi.

### 3.4 Persistence — yangi jadvallar (Postgres)
```
process_definition(id, name, version, bpmn_xml, parsed_model jsonb, created_at)
process_instance(id, definition_id, business_key, status, created_at, ...)
execution_token(id, instance_id, current_node, status, ...)
token_variable(id, instance_id, name, value jsonb, ...)
job(id, instance_id, token_id, type, payload jsonb, status, attempts, run_at, ...)   -- async/timer
```
> Eski engine jadvallaridan **butunlay ajratilgan**. Indekslar boshidan to'g'ri (business_key, instance, run_at).

### 3.5 Async / resilience
- Og'ir/tashqi qadam → `job` yoziladi → RabbitMQ → consumer virtual thread'da ishlaydi.
- **Idempotentlik:** job `business_key`/token bilan; qayta ishlash xavfsiz.
- **Retry + backoff**, **manual ack + dedup** (eski `resilience.md` qoidalari).
- Har tashqi chaqiruvda **timeout**.

---

## 4. Eski bilan yonma-yon ishlash (routing)
- Yangi dvigatel — **alohida servis/deployment** (yangi port/URL).
- **Input gateway** (Xazna/OFT/servicegateway) protsess-nomi/versiyaga qarab yo'naltiradi:
  - eski protsesslar (v1–v8) → **eski BPMS**;
  - yangi protsesslar (v9/v10) → **yangi BPMS**.
- Ikkalasi ham **bir xil connector maqsadi** (PHP app `creditconveyer.endpoint`) ga boradi — biznes o'zgarmaydi.
- Migratsiya: protsess-protsess yangi engine'ga ko'chiriladi; eski buzilmaydi.

---

## 5. Bosqichli yetkazish (MVP → to'liq)
| Bosqich | Mazmun |
|---|---|
| **0 — Skeleton** | Boot 3.2/Java 21 app, Hexagonal paketlar, DB sxema, healthcheck, Virtual Threads yoqilgan |
| **1 — MVP ijro** | BPMN parse + deploy + **chiziqli** ijro (start → serviceTask → end), type-safe connector dispatch, 1 connector adapter |
| **2 — Gateway** | exclusive/parallel gateway, sequence flow shartlari, expression baholash |
| **3 — Async** | RabbitMQ job, retry/backoff, idempotentlik, timeout |
| **4 — State/Timer** | token-state persistence, recovery (qayta ishga tushirishga chidamli), timer, callback |
| **5 — Parity** | `TUNE_CREDIT_REQUEST_6004`ni ikkala engine'da **shadow-run**, natijalarni solishtirish |
| **6 — Rollout** | input-gateway versiya-routing; v9 protsessni yangi engine'da jonli; monitoring |

> **MVP tamoyili:** avval **haqiqiy protsesslar ishlatadigan BPMN qism to'plamini** qo'llang (hamma BPMN spetsifikatsiyasini emas). Kredit-skoring oqimida qaysi element/gateway ishlatilsa — o'shalar.

---

## 6. Risklar va yumshatish
| Risk | Yumshatish |
|---|---|
| Eski engine bilan **xatti-harakat farqi** (gateway/token semantikasi) | **Shadow-run** (bir xil kirish → ikkala engine → natijani solishtirish) rollout'dan oldin; characterization test |
| **Scope creep** (butun BPMN spetsifikatsiyasi) | Faqat kerakli element to'plami (MVP), qat'iy chegara |
| Timer/edge-case'lar | Bosqich 4'da alohida, testlar bilan |
| Ma'lumot/holat migratsiyasi | Yangi engine faqat **yangi** instance'larni ishlaydi (eski instance'lar eski engine'da tugaydi) — migratsiya YO'Q |
| Virtual Threads pinning (synchronized/native) | I/O joylarida `synchronized` o'rniga `ReentrantLock`; profiling |

---

## 7. Java 21 / Boot 3 dan foydalanish (xulosa)
- **Virtual Threads** — bloklovchi connector I/O uchun (asosiy unumdorlik yutug'i).
- **Records** — DTO/VO (immutable, qisqa).
- **Sealed interfeys + pattern matching switch** — BPMN element turlari, gateway turlari.
- **Boot 3.2+** — `jakarta.*`, zamonaviy Actuator/Micrometer (Tier 2 metrika bilan mos).

## 8. Definition of Done (yuqori daraja)
- [ ] Yangi Boot 3.2/Java 21 modul, Hexagonal paketlar, Virtual Threads yoqilgan.
- [ ] BPMN parse + deploy + ijro (start/serviceTask/gateway/end).
- [ ] Type-safe connector registry (getBean(String) yo'q) + v9 connector adapter.
- [ ] Async job (RabbitMQ) + retry/idempotentlik + timeout.
- [ ] Yangi DB sxema (indeksli), token-state persistence + recovery.
- [ ] `TUNE_CREDIT_REQUEST_6004` shadow-run: yangi ≡ eski natija.
- [ ] Input-gateway versiya-routing; v9 yangi engine'da; monitoring/log (Tier 2).
- [ ] Docs (yangi engine arxitekturasi, connector qo'shish, routing).

## 9. Ochiq savollar
1. Yangi engine **alohida repo**mi yoki mavjud bpms repo ichida yangi modul (`bpms-v2`)? (Tavsiya: alohida deployment; repo tanlovi jamoaga bog'liq.)
2. Connector'lar: v9'dagilarni **adapter** bilan qayta ishlatamizmi yoki yangi `Connector` interfeysida qayta yozamizmi? (Tavsiya: adapter — takror ish yo'q.)
3. Qaysi BPMN elementlari **haqiqatan kerak** (MVP qamrovi) — mavjud protsesslardan ro'yxat kerak (start, end, serviceTask, exclusive/parallel gateway, timer, ...).
4. Boshlash sharti: bu loyiha **v9 jonli va barqaror** bo'lgach boshlanadimi (tavsiya) yoki parallelmi?
