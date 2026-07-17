# Plan 13 — Umumiy BPMS platformasi (mustaqil project, qayta ishlatiladigan)

> Maqsad: **bitta umumiy BPMN dvigatel platformasi** — turli domen-project'lar (credit-conveyor, docflow, client-appeals,
> va boshqalar) **asosiy yadroga tegmasdan** undan foydalanadi, o'z connector/service'larini ulaydi, o'zicha
> kengaytiradi; xohlasa **o'z executor'ini** ishlatadi. Tashqi **vizual-modeler**lar BPMS'dan mavjud connector/service
> va ularning inputlarini **API orqali** olib, aniq sxema qura oladi.
> Stek: **Java 21, Spring Boot 3.5.14, Virtual Threads, ko'p-modul (multi-module), SPI + Hexagonal**. Maksimal **abstract**.
>
> **Vaqt:** bu — **kelajak loyihasi**. Hozir boshlanmaydi; **v9 tugatilib testga topshirilgach**, imkon bo'lsa boshlanadi.
> **Ko'p-format (maqsad):** parser abstraktsiyasi (§3.1) orqali **Camunda-formatli BPMN** ham, kelajakda **o'z
> custom formatimiz** ham qabul qilinadi. Boshqa jamoalar (BPMS'da 10–50+ sxema) eski Camunda sxemalarini **qayta
> yozmasdan** ishlatadi; biz esa o'z formatimizni ham parallel qo'sha olamiz — core o'zgarmaydi.

---

## 0. Vizyon va prinsiplar
1. **Platforma, product emas bir loyiha:** yadro **hech qaysi domenni bilmaydi**. Credit-conveyor ham, docflow ham,
   appeals ham — **tashqi domen**, yadroga plug bo'ladi.
2. **Abstraktsiya (SPI):** yadro faqat **interfeys/port**lar bilan ishlaydi. Domen o'zining connector/persistence/executor
   implementatsiyasini beradi — **yadro kodi o'zgarmaydi** (Open-Closed).
3. **Ikki rejim:** (a) **Embedded** — domen app yadroni kutubxona sifatida ulaydi va **o'z executor'ini** ishlatadi;
   (b) **Markaziy server** — bitta BPMS server, domenlar unga connector/process registratsiya qiladi.
4. **Discovery-first:** BPMS o'zida ro'yxatdan o'tgan connector/service va ularning inputlarini **API orqali** beradi
   (vizual-modeler uchun palette + validatsiya).
5. **Izolyatsiya:** har domen o'z **namespace**ida (process, connector, variable, DB-sxema) — bir-biriga xalal bermaydi.
6. **Java 21:** Virtual Threads (bloklovchi I/O), records, sealed + pattern matching.

---

## 1. Tarqatish rejimlari (distribution)
```
(a) EMBEDDED                              (b) MARKAZIY SERVER
 domen-app (credit-conveyor)              bpms-server (standalone)
   └─ bpms-spring-boot-starter  ◄──┐        ├─ REST API (deploy/start/catalog/validate)
   └─ o'z connector'lari           │        ├─ engine + default adapterlar
   └─ o'z executor (in-process)    │        └─ domenlar connector'larini registratsiya qiladi
                                   │           (jar plug yoki remote worker)
      yadro = kutubxona ───────────┘
```
> Domen **o'zi tanlaydi**: yadroni embed qilib o'z executor'ini yuritadi, yoki markaziy serverdan foydalanadi.

---

## 2. Modul tuzilishi (multi-module Maven/Gradle)
```
bpms-platform/
├── bpms-core/            (SOF domen: BPMN model + ijro semantikasi. Spring YO'Q, DB YO'Q)
├── bpms-spi/             (kengaytirish interfeyslari: Connector, ConnectorCatalog, portlar)
├── bpms-engine/          (orkestratsiya: core + portlarni bog'laydi; ExecutionEngine)
├── bpms-persistence-jpa/ (default outbound adapter: Postgres/JPA)
├── bpms-queue-rabbit/    (default async adapter: RabbitMQ job)
├── bpms-catalog/         (discovery: connector/service metadata + API modeli)
├── bpms-spring-boot-starter/ (EMBEDDED rejim: auto-config — domen faqat shu starter'ni qo'shadi)
├── bpms-server/          (MARKAZIY rejim: runnable app, REST, catalog, validate)
└── bpms-bom/             (versiyalar BOM — domenlar bitta joydan versiya oladi)
```
> `bpms-core` va `bpms-spi` — **frameworksiz** (sof Java). Shu sabab domen ularni istalgan muhitda ishlatadi.

---

## 3. Yadro (bpms-core) — BPMN elementlari
Realizatsiya qilinadigan asosiy elementlar (sealed + records, Java 21):
```java
public sealed interface FlowNode permits
    StartEvent, EndEvent, ServiceTask, UserTask, ScriptTask,
    ExclusiveGateway, ParallelGateway, InclusiveGateway,
    SubProcess, TimerEvent, MessageEvent, SignalEvent { }
```
- **Event:** start, end, timer, message, signal (bosqichma-bosqich).
- **Task:** serviceTask (connector), scriptTask (expression), userTask (tashqi tugallash).
- **Gateway:** exclusive (shart), parallel (fork/join), inclusive.
- **SubProcess**, sequence flow + shart-expressionlar.
- **Ijro modeli:** ExecutionToken (graf bo'ylab yuradi), TokenVariables (namespaced), gateway semantikasi.
- Element dispatch — `switch` pattern matching (`case ServiceTask st -> ...`).

### 3.1 Format-agnostik parser (Camunda + o'z formatimiz) — kengaytiriladigan
Dvigatel **ichki model** (`bpms-core` `ProcessDefinition`) bilan ishlaydi — **format**ni bilmaydi. Har format uchun
alohida **parser** uni yagona ichki modelga o'giradi (Ports & Adapters / Strategy):
```java
public interface ProcessDefinitionParser {
    boolean supports(SourceFormat format);     // Camunda-XML? Custom (JSON/DSL)?
    ProcessDefinition parse(byte[] source);    // → yagona ICHKI model
}
class CamundaBpmnParser  implements ProcessDefinitionParser { ... }   // hozirgi Camunda-XML
class CustomFormatParser implements ProcessDefinitionParser { ... }   // kelajakdagi O'Z formatimiz
// ParserRegistry — formatni aniqlab (root-element / extension / explicit param) to'g'ri parser'ni tanlaydi
```
- **Ikkala format ham bir vaqtda** qabul qilinadi; yangi format = **yangi parser** qo'shish (core/engine **tegilmaydi**).
- Ichki model **superset** bo'lishi kerak — ikkala format elementlarini ifodalay olsin. O'z formatimiz yangi tushuncha
  qo'shsa, ichki modelga **additiv** kengaytiriladi.
- Bonus: ichki model **pivot** bo'lgani uchun kelajakda **format o'zaro konvertatsiya** (Camunda ↔ custom) ham mumkin.

---

## 4. SPI — kengaytirish nuqtalari (bpms-spi) — "abstract" o'zagi
Domen faqat shularni implementatsiya qiladi; yadro tegilmaydi:
```java
// Domen connector'i
public interface Connector {
    String id();
    ConnectorResult execute(ConnectorContext ctx);   // ctx: token, inputlar, variables
    ConnectorDescriptor describe();                    // inputlar (catalog + validatsiya uchun)
}
// Connector'larni yetkazuvchi (ServiceLoader yoki Spring bilan)
public interface ConnectorProvider { Collection<Connector> connectors(); }

// Outbound portlar (default impl bor, domen almashtira oladi)
public interface DefinitionRepositoryPort { ... }
public interface InstanceRepositoryPort   { ... }
public interface VariableStorePort        { ... }
public interface JobQueuePort             { ... }
public interface ExpressionEvaluatorPort  { ... }
public interface ClockPort                { ... }

// Domen o'z EXECUTOR'ini bersa
public interface ExecutorPort { void submit(Runnable task); }   // default: Virtual Threads
```
> **Auto-discovery:** connector'lar Java `ServiceLoader` (SPI) yoki Spring auto-config orqali topiladi — domen jar'ini
> qo'shsa, uning connector'lari **avtomatik** ro'yxatga tushadi va **catalog'ga chiqadi**. Yadro kodi o'zgarmaydi.

---

## 5. Connector modeli + type-safe registry
```java
@Component  // yoki SPI
class ConnectorRegistry {
    private final Map<String, Connector> byId;   // barcha domen connector'lari, startup'da yig'iladi
    // getBean(String) YO'Q → noto'g'ri connectorId startup'da bilinadi
}
```
- Namespace: `domain:connectorId` (masalan `creditConveyer:GetScoringResultV9`) — domenlar to'qnashmaydi.
- Ijro: `ExecutorPort` (default Virtual Threads) — bloklovchi connector I/O arzon parallel.
- Domen **o'z executor'ini** bersa — o'sha ishlatiladi (izolyatsiya/limit uchun).

---

## 6. Discovery / Catalog API (vizual-modeler uchun — kelajak talabi)
BPMS o'zida realizatsiya qilingan connector/service'lar va inputlarini beradi → tashqi BPMN-dizayner aniq ma'lumot
bilan sxema quradi:
```
GET  /catalog/connectors                 → [{id, domain, description, inputs:[{name,required,type,description}], outputs}]
GET  /catalog/connectors/{id}            → bitta connector to'liq deskriptori
GET  /catalog/domains                    → ro'yxatdan o'tgan domenlar
POST /schema/validate                    → yuklangan BPMN'ni connector/input bo'yicha tekshirish (hisobot)
```
- Manba: har connector'ning `describe()` (Tier 1 `@ConnectorInput` deskriptori).
- Vizual-modeler: `/catalog/connectors`dan **palette** to'ldiradi; `/schema/validate` bilan sxemani deploy'gача tekshiradi.
- Natija: modeler'da faqat **haqiqatan mavjud** connector va **to'g'ri input nomlari** ko'rsatiladi → xato kamayadi.
- **Modeler-agnostik:** hozir **Camunda Modeler** ishlatilyapti — catalog metadata'ni Camunda "element templates" shaklida
  ham berish mumkin (hozirgi modeler bilan ishlashi uchun). Kelajakda **custom vizual-modeler** yozilganda xuddi shu
  API'dan foydalanadi (o'zgarish shart emas).

---

## 7. Persistence & Async (default adapter + pluggable)
- **Default:** `bpms-persistence-jpa` (Postgres) + `bpms-queue-rabbit`. Jadvallar namespace/domain bilan.
  ```
  process_definition(domain, name, version, bpmn_xml, parsed jsonb)
  process_instance(id, domain, definition_id, business_key, status, ...)
  execution_token(id, instance_id, current_node, status)
  token_variable(id, instance_id, name, value jsonb)
  job(id, instance_id, token_id, type, payload jsonb, status, attempts, run_at)
  ```
- **O'zgaruvchi saqlash (`VariableStorePort`) — ABSTRACT (asosiy talab):** o'zgaruvchilar **qanday** saqlanishi domen
  tanlovi. **Default: EAV** (Entity-Attribute-Value) — sizdagi loyihalar kabi (domenga xos jadval SHART EMAS).
  Boshqa loyiha jadval-asosli yoki jsonb saqlashni xohlasa — `VariableStorePort`ni o'z implementatsiyasi bilan
  almashtiradi. **Yadro qanday saqlanishini bilmaydi** (faqat port bilan ishlaydi).
- **Pluggable:** domen boshqa persistence/queue xohlasa — `...Port`ni implementatsiya qiladi (yadro o'zgarmaydi).
- **Resilience:** timeout, retry+backoff, idempotentlik (job business_key), manual ack + dedup.

---

## 8. Domen izolyatsiyasi (multi-domain)
- Har domen: alohida **namespace** (process/connector/variable/DB-sxema yoki jadval prefiksi).
- Har domen: xohlasa **o'z executor pool** (ExecutorPort) — resurs limiti/izolyatsiya.
- Embedded rejimda domen **butunlay o'zining** engine instansiyasini yuritadi; markaziy rejimda umumiy server, lekin
  namespace bilan ajratilgan.

---

## 9. Java 21 / Spring Boot 3.5.14
- **Virtual Threads** — connector/HTTP bloklovchi I/O (asosiy unumdorlik). `bpms-core`/`engine` executor'i default VT.
- **Records** — ConnectorContext/Result, DTO/VO.
- **Sealed + pattern matching** — FlowNode/Gateway turlari.
- **Boot 3.5.14 auto-configuration** — `bpms-spring-boot-starter` (`@AutoConfiguration`) → domen faqat starter'ni qo'shadi,
  connector'larini beradi, ishlaydi.
- `jakarta.*`, Actuator/Micrometer (metrika/observability).

---

## 10. Bosqichli yetkazish
| Bosqich | Mazmun |
|---|---|
| 0 | Multi-module skeleton (`core/spi/engine/starter/server`), Boot 3.5.14/Java 21, VT, BOM |
| 1 | `bpms-core` BPMN model + chiziqli ijro (start→serviceTask→end); type-safe connector registry; ServiceLoader/Spring discovery |
| 2 | Gateway (exclusive/parallel/inclusive), sequence-flow shartlari, expression port |
| 3 | Persistence (JPA) + async (RabbitMQ job) + retry/idempotentlik/timeout |
| 4 | Timer/message/signal event, subProcess, token-state recovery |
| 5 | **Catalog + validate API** (discovery, vizual-modeler uchun) |
| 6 | `bpms-spring-boot-starter` (embedded) + `bpms-server` (markaziy) ikki rejim |
| 7 | Domen izolyatsiyasi (namespace, per-domain executor) |
| 8 | **Namuna domen:** credit-conveyer'ni platformaga plug qilish (v9 connector'lar adapter bilan); parity/shadow-run |

---

## 11. Domen qanday ulanadi (misol — hech qaysi core fayl tegilmaydi)
```java
// credit-conveyor domeni:
@Component
class GetScoringResultConnector implements Connector {
    public String id() { return "creditConveyer:GetScoringResult"; }
    public ConnectorResult execute(ConnectorContext ctx) { /* PHP /score chaqiradi */ }
    public ConnectorDescriptor describe() { /* token, IsSuccess, Response... */ }
}
// pom: bpms-spring-boot-starter qo'shiladi → connector avtomatik registratsiya + catalog'ga chiqadi.
```
`docflow`, `client-appeals` — xuddi shunday: o'z connector'larini beradi, o'z executor'i/namespace'ini tanlaydi.
**Core, engine, catalog — hech biri o'zgarmaydi.**

---

## 12. Definition of Done (yuqori daraja)
- [ ] Multi-module platform (core/spi/engine/persistence/queue/catalog/starter/server/bom), Boot 3.5.14/Java 21, VT.
- [ ] BPMN asosiy elementlari (event/task/gateway/subprocess/flow) ijro qilinadi.
- [ ] SPI: Connector + ConnectorProvider + outbound portlar; ServiceLoader/Spring auto-discovery.
- [ ] Type-safe connector registry (getBean(String) yo'q); namespace bilan.
- [ ] Default JPA + RabbitMQ adapter; portlar orqali almashtiriladigan.
- [ ] Discovery/Catalog API (`/catalog/connectors`, `/schema/validate`) — vizual-modeler uchun.
- [ ] Embedded (starter) va markaziy (server) rejim; per-domain executor.
- [ ] Namuna: credit-conveyer domeni core'ga tegmasdan plug bo'lgan; shadow-run parity.
- [ ] **Ko'p-format parser** (`ProcessDefinitionParser` + registry): Camunda-XML ishlaydi; o'z formatimiz parser qo'shish bilan (core tegilmasdan).
- [ ] Docs: platforma arxitekturasi + "yangi domen qanday ulanadi" + catalog API.

## 13. Tasdiqlangan qarorlar
1. ✅ Discovery — **ikkalasi:** `bpms-core`/`bpms-spi` sof Java **ServiceLoader (SPI)**; `bpms-spring-boot-starter` Spring
   auto-config bilan ko'prik. Frameworksiz ham, Spring bilan ham ishlaydi.
2. ✅ Catalog **modeler-agnostik.** Hozir Camunda Modeler (element templates shaklida ham); kelajakda **custom modeler**
   xuddi shu API'dan foydalanadi.
3. ✅ (Q3 izohi) "Embedded vs markaziy" = tarqatish rejimi: (a) domen yadroni **kutubxona** qilib **o'z ichida** yuritadi,
   (b) bitta **markaziy BPMS server** hammaga xizmat qiladi. **Hozir qaror shart emas** — bu ish v9'dan keyin. Boshlaganda
   **embedded starter'dan** boshlash tavsiya (soddaroq).
4. ✅ Persistence — **maksimal abstract.** O'zgaruvchi saqlash `VariableStorePort` ortida; **default EAV** (sizdagi kabi),
   jadval/jsonb — boshqa loyiha uchun pluggable. DB tanlovi (umumiy namespace vs per-domain) implementatsiyada beriladi;
   embedded'da odatda per-domain, markaziyda namespace bilan umumiy.
5. ✅ **Ko'p-format (§3.1).** `ProcessDefinitionParser` abstraktsiyasi: **Camunda-XML** hozir qabul qilinadi (boshqa jamoalar
   10–50+ sxemani qayta yozmaydi); **o'z custom formatimiz** ham keyin **parallel** qo'shiladi — yangi parser, core tegilmaydi.
   Ichki model pivot → format o'zaro konvertatsiya ham mumkin.

> **Boshlash sharti:** v9 tugatilib **testga topshirilgach** (§ yuqoridagi "Vaqt" qaydi).

---

## 14. BPMN 2.0.2 konformlik (OMG spec bilan moslik)
> Manba: **OMG BPMN 2.0.2** (https://www.omg.org/spec/BPMN/2.0.2/). **To'liq amalga oshirish EMAS** — ishlatiladigan
> qism-to'plam uchun **spec-semantika + standart XML**. Maqsad: o'zaro moslik + to'g'ri ijro, o'zboshimcha model emas.

### 14.1 Maqsad konformlik-sinfi
- **Process Modeling Conformance** (serializatsiya/XML) — to'liq: fayllar standart BPMN 2.0 XML.
- **Process Execution Conformance** — **qism-to'plam**: faqat qo'llab-quvvatlanadigan elementlar semantikasi (14.3).

### 14.2 Standart XML / XSD
- Parse va validatsiya **rasmiy BPMN 2.0 XSD**ga tayanadi (parser §3.1).
- **Camunda XML = BPMN 2.0 XML + camunda extension elements** → kengaytmalar (connector metadata, va h.k.) `extensionElements` orqali qo'llab-quvvatlanadi.

### 14.3 Qamrov — element holati (MVP)
| Element (spec nomi) | Holat |
|---|---|
| Start/End Event (none) | Supported |
| Sequence Flow (+ conditional, default) | Supported |
| Service Task (connector) | Supported |
| Script Task / User Task | Supported (keyinroq) |
| Exclusive Gateway (data-based XOR) | Supported (default-flow bilan) |
| Parallel Gateway (AND fork/join) | Supported (token-join semantikasi) |
| Inclusive Gateway (OR) | Supported yoki Deferred (join nozik — 14.4) |
| Sub-Process | Supported (keyinroq) |
| Timer/Message/Signal/Error/Boundary Event | Bosqichma-bosqich (dastlab Deferred) |
| Event-based Gateway, Complex Gateway, Compensation | Deferred |
| Pool/Lane (Collaboration), Choreography | Deferred (qamrov tashqarisida) |

### 14.4 Token/Gateway semantikasi (eng muhim — spec'ga AYNAN)
- **Parallel join:** barcha kiruvchi tokenlar kelmaguncha kutadi (deadlock yo'q).
- **Exclusive:** bitta yo'l tanlanadi; hech biri mos kelmasa **default flow**.
- **Inclusive join:** faol tokenlar to'plamiga qarab (spec qoidasiga aniq amal — homegrown engine'lar aynan shu yerda xato qiladi).
- Bu qism uchun **konformlik testlari** (spec misollaridan) yoziladi.

### 14.5 Hujjatlangan chetlanishlar
- **Qo'llab-quvvatlanmaydigan** (14.3 dagi Deferred) elementlar — aniq ro'yxat; sxema import'da bunday element bo'lsa **validatsiya ogohlantiradi** (Tier-1 catalog/validate bilan bir xil).
- Har qanday **qasddan chetlanish** (masalan camunda-connector kengaytmasi) — hujjatlanadi.

### 14.6 DoD (konformlik)
- [ ] Maqsad konformlik-sinfi + qamrov jadvali (Supported/Deferred) hujjatlangan.
- [ ] Parse/validatsiya **BPMN 2.0 XSD** asosida; camunda extensionElements qo'llanadi.
- [ ] Gateway/token semantikasi **spec konformlik-testlari** bilan qoplangan.
- [ ] Import'da qo'llab-quvvatlanmaydigan element → aniq **validatsiya ogohlantirishi**.
- [ ] Chetlanishlar hujjatlangan.
