# Plan 14 — Schema-moslik strategiyasi (eski Camunda sxemalar yangi engine'da minimal o'zgarish bilan yuklanishi)

> **Maqsad:** yangi engine (plan 12/13 yo'nalishi, Java 21/Boot 3, yonma-yon) yozilganda, boshqa guruhlar (HR/hrms
> va boshqa domenlar, 10–50+ sxema) ishlatayotgan **mavjud Camunda BPMN sxemalari** yangi engine'ga **qayta
> yozilmasdan** (yoki juda minimal o'zgarish bilan) deploy bo'lib, **bir xil xatti-harakat** bilan ishlashi kerak.
>
> **Kalit tamoyil (compatibility contract):** yangi parser eski engine'ning **Camunda dialektini** (u aynan qaysi
> element/extension/atributni o'qishini va qanday ijro semantikasini kutishini) **takrorlashi** shart. "Minimal
> o'zgarish" — bu maqsad EMAS, balki **o'lchanadigan chegara**: aniq nechta va qaysi sxema element'i yangi
> parser'da qamrab olinmagani hujjatlanadi va nolgacha kamaytiriladi.
>
> **Bu reja plan 12/13 bilan bog'liq:** 12/13 — "yangi engine qanday quriladi"; 14 — "yangi engine eski sxemalarni
> qanday yutadi". 14 plan 12/13'ning §14 (BPMN konformlik) qamrov jadvalini **tuzatadi** (§7'ga qarang).

---

## 0. Nega bu reja alohida kerak (muammoning aniq ta'rifi)

Foydalanuvchi (credit-conveyor) amalda faqat **serviceTask + exclusiveGateway + timer** ishlatgan. Shu sabab plan
12'ning MVP-tamoyili ("faqat kredit-skoring ishlatadigan qism-to'plam") tor. **Ammo bir xil BPMS backend'ni boshqa
guruhlar** (hrms/labour: ta'til, kandidat, ishdan bo'shatish, ko'p-imzo zanjiri) ishlatadi va ular **ancha keng**
element to'plamidan foydalanadi.

Yangi engine "yonma-yon" bo'lib, boshqa guruhlarni ham ko'chirmoqchi bo'lsak (plan 13 platforma vizyoni), unda
yangi parser **eski sxemalarni o'qiy olishi shart** — aks holda ular uchun engine yaroqsiz. Shu sabab **compat
scope'i idealizatsiya qilingan MVP bilan emas, ikki manba bilan aniqlanadi:**

1. **Eski engine amalda qaysi element/extensionni parse qiladi** (quyida §1 — kod o'qib tasdiqlangan).
2. **Boshqa guruhlar sxemalari amalda qaysilarini ishlatadi** (§4 — ulardan inventar yig'iladi; ba'zi qismlar
   local repo'da yo'q, tasdiqlash kerak).

Compat target = **(1) ∩ (2)** — ya'ni eski engine o'qiydigan VA kimdir haqiqatan ishlatadigan hamma narsa.

---

## 0.1 Joylashuv va Cursor uchun QAT'IY QOIDA (guardrail) — MAJBURIY

- **Yangi engine papkasi:** `bpms/bpms-new-backend/` (yangi, hozircha bo'sh papka — `bpms/liveScoring-bpms-backend/`
  yonida). **Barcha yangi kod faqat shu papkada** yoziladi.
- **Stack (QAT'IY):** **Java 21** (records, sealed, pattern-matching switch, Virtual Threads) + **Spring Boot 3.5.14**
  (`jakarta.*`, `javax.*` EMAS). Bu **noldan yangi Boot 3 loyihasi** — Boot 2→3 migratsiya EMAS. Arxitektura:
  Hexagonal (Ports & Adapters) + SPI, type-safe connector registry (`Map<String,Connector>`, `getBean(String)` yo'q),
  god-class yo'q (plan 12/13).
- **Eski engine `bpms/liveScoring-bpms-backend/` — READ-ONLY (standart himoya):** u v1–v8 (prod) + v9 bilan jonli
  ishlayapti. **Yangi-engine ishida** Cursor uni **faqat o'qiydi** (dialekt/parity faktlari uchun — §1), **tasodifan
  ham tahrir/o'chirish/ko'chirish qilmaydi**.
  - **Override (muhim):** eski engine'ni o'zgartirish **taqiqlanmagan** — bu **alohida vazifa**. Agar chatdagi aniq
    topshiriq eski engine'ni o'zgartirish bo'lsa (masalan v9 connector ishi), bu **ruxsat**. Read-only qoidasi faqat
    yangi-engine qurayotganda **tasodifiy** tahrirni oldini oladi, maqsadli maintenance'ni emas. Bu repo'da **ikki
    parallel yo'nalish** bor: (a) eski engine maintenance, (b) yangi engine — chatdagi topshiriqdan qaysi biri ekanini
    aniqlang; noaniq bo'lsa so'rang.
- **Ikki alohida deployment:** yangi engine mustaqil ishga tushadi (yangi port + yangi DB sxema, eski jadvallardan
  ajratilgan). Eski bilan aloqa — faqat input-gateway versiya-routing (plan 12 §4); **kod-darajasida aralashuv yo'q**.
- **Git:** har sub-repo alohida (`BPMS_OPTIMIZATION_CONTEXT` §8). Yangi papka o'z commit'lari bilan; eski repo'ga
  **commit yo'q**. (Bu plan 12 §9-Q1 "alohida repo/modul" savolini hal qiladi: **alohida papka `bpms-new-backend`**.)
- **Cursor'ga birinchi ko'rsatma (system/rules sifatida bering):** *"Manba faqat o'qish uchun:
  `bpms/liveScoring-bpms-backend`. Yozish/tahrir faqat: `bpms/bpms-new-backend`. Eski papkadagi hech narsani
  o'zgartirma."*

---

## 1. Tasdiqlangan fakt — eski engine `.bpmn` dialekti (kod o'qib aniqlangan)

> Manba: `bpms/liveScoring-bpms-backend/.../service/BpmnService.java`, `ActivityService.java`, `EventService.java`,
> `GatewayService.java`, `FlowService.java`, `ListenerTaskService.java`, `InstanceTokenService.java`,
> `helpers/BpmHelper.java`, `helpers/SystemHelper.java`. Eski engine Camunda BPMN Model API'ni **faqat XML parser**
> sifatida ishlatadi; ijro — o'zining custom dvigateli (`com.bpmn`).
>
> **Verify natijalari (2026-07-15):** oldingi ⚠️-belgilangan noaniqliklarning kod bilan yopiladiganlari hal qilindi
> — connectorId tashuvchisi (§1.2), event-definition to'plami (§1.4), expression evaluator (§1.5), multi-instance
> (§1.1). Bittasi **oldingi taxminni tuzatdi:** evaluator custom til emas, **Spring SpEL**.

### 1.1 Element qamrovi (`BpmnService.importFlowElement` — Process va SubProcess uchun **bir xil** dispatch)

| BPMN element | Eski engine | Izoh (dialekt) |
|---|---|---|
| StartEvent / EndEvent | ✅ | message/timer start (§1.4) |
| Task (turi yo'q / none) | ✅ | abstrakt task |
| ServiceTask | ✅ | connector / delegateExpression / external-topic (§1.2) |
| UserTask | ✅ | forma: `camunda:formData` (§1.3) |
| ScriptTask | ✅ | `ScriptTaskService` |
| ManualTask / SendTask / ReceiveTask | ✅ | mos servislar bor |
| BusinessRuleTask | ✅ | `BusinessRuleTaskService` |
| **CallActivity** | ✅ | boshqa protsessni chaqirish |
| ExclusiveGateway | ✅ | `default` flow + shart (§1.5) |
| ParallelGateway | ✅ | fork/join |
| InclusiveGateway | ✅ | OR |
| **SubProcess** | ✅ | **ichma-ich** (rekursiv), ichida LaneSet ham bo'ladi |
| **Multi-instance** (`multiInstanceLoopCharacteristics`) | ✅ | **TASDIQLANGAN** — sequential/parallel, `loopCardinality`, `camunda:collection`, `camunda:elementVariable`, `completionCondition` (`BpmHelper.getMultiInstance`; `Activity.multiInstance`, `InstanceTokenState.parentMultiInstanceId`) |
| **BoundaryEvent** | ✅ | `attachedTo` (task chetiga ulanadi); definition faqat message/timer (§1.4) |
| **IntermediateThrowEvent / IntermediateCatchEvent** | ✅ | definition faqat message/timer (§1.4) |
| SequenceFlow | ✅ | shart-expression = SpEL (§1.5) |
| **LaneSet / Lane** | ✅ | **rekursiv** (lane ichida lane); tashkiliy taqsimot |
| **Collaboration / Participant / MessageFlow** | ✅ | **pool**lar; message flow throw↔catch bog'laydi |
| Message (Definitions darajasida) | ✅ | korrelyatsiya uchun |

> **MUHIM xulosa:** eski engine **serviceTask'dan ancha keng** — subProcess, **multi-instance** (ko'p-imzo uchun
> muhim), callActivity, pool/lane (collaboration), boundary + intermediate event, uch gateway, 8 task turi, message
> korrelyatsiya. Plan 12/13'ning "pool/lane, boundary, intermediate — Deferred" degani **compat uchun yaroqsiz** (§5).

### 1.2 ServiceTask → connector (dialekt) — **TASDIQLANGAN**
- ServiceTask **uch** implementatsiya turini o'qiydi (`BpmHelper`): `camunda:connector`, `camunda:delegateExpression`,
  `camunda:type="external"` (topic). Amalda asosiysi — **connector**.
- **Connector tashuvchisi (aniqlandi):** Camunda'ning rasmiy `connector` extension'i —
  `<camunda:connector><camunda:connectorId>BeanNomi</camunda:connectorId><camunda:inputOutput>…</camunda:inputOutput></camunda:connector>`.
  `connectorId` = `@Component("...")` bean nomi (`camundaConnector.getCamundaConnectorId().getTextContent()`).
  ⇒ **`camunda:property id="connectorId"` EMAS** (oldingi noaniqlik yopildi).
- Input/output: `camunda:inputParameter`/`camunda:outputParameter` (`camunda:inputOutput` ichida) →
  `ConnectorInputDTO`/`ConnectorOutputDTO` (`EntityHelper.getConnectorInput/OutputDTOListFrom...`).
- Runtime dispatch: `BpmExecutionService:630` — `ctx.getBean(connectorId)` (type-unsafe getBean). Yangi engine buni
  `Map<String,Connector>` registry bilan almashtiradi (plan 12 §3.1), **lekin XML o'qish qoidasi bir xil qoladi**.
- ℹ️ **Tier-1 validatsiya moduli allaqachon mavjud** (`validation/registry/ConnectorRegistry`,
  `validation/validator/BpmnSchemaValidator`, `ConnectorCatalogEntry`, Levenshtein-taklif — plan 09 bajarilgan
  ko'rinadi): noto'g'ri connectorId + missing/unknown input deploy'da tekshiriladi. Yangi engine shu registry'ni
  qayta ishlatadi (compat + catalog API uchun tayyor asos).

### 1.3 UserTask → forma (dialekt) — **eng nozik qism**
- `camunda:formData` / `camunda:formField id/label/type/defaultValue`. `type` bo'yicha tarmoqlanish
  (`BpmnService.importFormField`):
  - `string|json|boolean|date|long|enum` → typed servislar;
  - **boshqa har qanday satr** (`single_data`, `async_data`, `buttons`, `bpmn_collect_data`, `warning_text`, ...) →
    `FormFieldCustomType` (`fieldType: CUSTOM_TYPE`). **Yangi field turi qo'shishga backend o'zgarmaydi.**
- `properties` = **tekis** `id→string` map (`camunda:property`), `validations` = **tekis** `name→config` string map
  (`camunda:constraint` → `getCamundaName()/getCamundaConfig()`) — `BpmHelper` har doim `String` oladi.
- ❗ **Hal qilinmagan noaniqlik (bpms-new Faza 0):** `bpmn_collect_data`ning nested `parameters: [...]` XML'da
  qanday saqlanadi (escaped-string = Variant A, yoki boshqa mexanizm = Variant B). **Yangi parser buni AYNAN eski
  engine kabi qilishi shart** — aks holda HR formalar sezilmagan holda buziladi. Bu compat uchun **bloklovchi**
  savol (§4.1) — dev-deploy testi kerak, kod o'qish bilan yopilmaydi.

### 1.4 Event (dialekt) — **TASDIQLANGAN**
- Faqat **ikki** event definition o'qiladi (`EventService`): `MessageEventDefinition` (message-ref/code/name;
  `MessageEventTypeEnum.START`) va `TimerEventDefinition` (`BpmHelper.getTimerType/getTimerValue` →
  `timeDate` / `timeDuration` / `timeCycle`).
- **Signal / Error / Escalation / Compensate / Conditional / Link — YO'Q** (butun kod bo'yicha `*EventDefinition`
  grep **bo'sh**). Boundary/intermediate event **element** sifatida import qilinadi, lekin definition darajasida
  faqat message/timer semantikasi bor.
- **Compat oqibati:** agar boshqa guruh sxemasi error/signal event ishlatsa, eski engine ularni **semantikasiz**
  yutadi (jim). Yangi `CamundaCompatParser` **bir xil** qilishi yoki **warning** berishi kerak. Ular buni ishlatadimi —
  §4.1(2) real `.bpmn` fayllardan aniqlanadi (ishlatmasa — muammo yo'q).

### 1.5 SequenceFlow shart + gateway default + **EXPRESSION EVALUATOR** (dialekt) — **TASDIQLANGAN (taxmin tuzatildi)**
- `FlowService`: har flow `BpmHelper.getCondition(sequenceFlow)` → `ConditionDTO(type=EXPRESSION)`; ExclusiveGateway
  `gateway.getDefault()` → hech bir shart mos kelmasa **default flow**.
- **Evaluator = Spring SpEL** (`SpelExpressionParser` + `StandardEvaluationContext` + `MapAccessor`) —
  `InstanceTokenService.expressionExecuteResult`. ⇒ **Custom til EMAS** (oldingi "o'z tili" taxmini noto'g'ri edi),
  **Camunda JUEL ham emas** — o'rtada, Spring SpEL.
- ⚠️ **Kritik quirk `isExprStr` (SystemHelper):** satr `${...}` bilan belgilanmaydi. Satrda quyidagi belgilardan
  **biri** bo'lsa → SpEL ifoda sifatida baholanadi, aks holda **literal** qaytariladi:
  `+  -  *  /  .  ,  $  '  "`. Ya'ni `"APPROVED"` → literal satr; `"a.b"`, `"x + 1"`, `"$var"` → ifoda. Yangi engine
  shu **aynan heuristikani** takrorlashi shart (aks holda shart/qiymat boshqacha talqin qilinadi).
- **Kontekst populatsiyasi:** instance o'zgaruvchilari HashMap'ga yuklanadi va **MapAccessor** orqali property sifatida
  ochiladi. Maxsus `$`-o'zgaruvchilar (ifodada ishlatilsa lazy qo'shiladi): `$businessKey`, `$processName /
  $processCode / $processId`, `$instanceTokenId / $instanceTokenStateId`, `$instanceCreatedDate /
  $instanceStateCreatedDate`. Agar ifodada `bpms.` bo'lsa — `ExecutionExecutiveHelper` bean **`bpms`** nomi bilan
  kontekstga qo'shiladi (bu **script-namespace mexanizmi** — §1.6; namespace ichidagi domen-metodlar yangi engine'da
  yadroga hardcode qilinmaydi, domen o'zi beradi).
- **Xato semantikasi:** ifoda parse/baholashda xato → `null` → shart uchun **`false`** (jim yutiladi). Parity uchun
  muhim: buzuq ifoda exception bermaydi, `false` beradi.
- ⇒ **Yaxshi xabar:** parity uchun homegrown til yozish shart emas — **o'sha SpEL** kutubxonasi + **o'sha `isExprStr`
  gate** + **o'sha kontekst-populatsiya** (`$`-vars + `bpms` bean + MapAccessor) + **xato→false** takrorlanadi.

### 1.6 Listener / script-hook (dialekt + yangi engine dizayni — **abstract namespace**)
- **Mexanizm (eski engine):** `ListenerTaskService` — `camunda:taskListener`/execution listener uch turda:
  `LANG_CLASS` (`camunda:class`), `EXPRESSION` (`camunda:expression`), `SCRIPT` (`camunda:script`, inline yoki
  `camunda:resource` external, `scriptFormat` bilan). Expression/script §1.5 dagi SpEL kontekstidagi `bpms`
  namespace orqali ijro etiladi.
- ⚠️ **Muhim aniqlik (foydalanuvchi, 2026-07-15):** eski BPMS'dagi `bpms.hrms.*` / `bpms.labour.*` — **boshqa domen**
  (HR) chaqiruvlari; ular bizning BPMS yadrosiga **xatolik tufayli aralashib qolgan** (cross-domain leakage). Bu
  **bizning domen uchun compat-target EMAS** — ularni yadroga ko'chirmaymiz.
- **Yangi engine dizayni — script-namespace = abstract SPI:** yadro `bpms.hrms` kabi domenni **bilmaydi**. Har domen
  o'z script-helper bean(lar)ini **SPI orqali** expression kontekstiga registratsiya qiladi (masalan
  `ScriptContextContributor` / `ScriptNamespaceProvider` — domen `namespace → bean` beradi). Shunda:
  - credit-conveyor **o'z** helper'ini beradi (masalan o'z namespace nomi ostida) — script qo'shishni ham domendan
    oladigan qilib **abstract** qilamiz;
  - HR domen plug bo'lsa — o'z `hrms` namespace'ini **o'zi** registratsiya qiladi (yadro emas);
  - hech bir domen boshqasining namespace'iga bog'lanmaydi (izolyatsiya — plan 13 §8).
- **Compat oqibati:** boshqa guruh sxemasidagi `${bpms.hrms.*}` ifodalari **faqat o'sha domen o'z namespace'ini
  registratsiya qilganda** ishlaydi — bu to'g'ri (yadro tozaligini buzmaydi). Yadro faqat **mexanizmni**
  (listener event turi + SpEL ijrosi + namespace-registratsiya SPI) beradi. Listener ijro-vaqti parity Faza 4'da.

### 1.7 O'zgaruvchi saqlash (EAV) — dialekt
- `InstanceVariable{String,Text,Integer,Double,Boolean,Date,Datetime,Time,Enum,Json}Service` — **typed EAV** (har tip
  alohida jadval/ustun). Yangi engine `VariableStorePort` (plan 13 §7) default EAV bo'lishi bu bilan mos —
  **lekin tiplar to'plami va nomlanishi** eski sxema kutganidek bo'lishi kerak (masalan `json` vs `text` farqi).

---

## 2. Compatibility contract — uch qatlam

Yangi parser **uch qatlamda** eski bilan mos bo'lishi kerak; har qatlam alohida test qilinadi:

1. **Sintaksis (XML surface):** qaysi element/extension/atribut o'qiladi (§1). Test: eski sxema fayllari yangi
   parser'da **xatosiz** ichki modelga o'giriladi; qamrab olinmagan har element **aniq ogohlantiradi** (jim yutmaydi).
2. **Struktura (ichki model mapping):** `camunda:formField.type` → CUSTOM_TYPE qoidasi, connector `inputOutput`
   mapping, listener turi, multi-instance, subprocess nesting, lane taqsimoti — eski engine qanday saqlagan bo'lsa
   **shunday** ichki modelga tushadi.
3. **Semantika (ijro):** gateway token qoidasi, default flow, **SpEL evaluator + `isExprStr` heuristikasi**, boundary
   event uzilishi, timer tetiklanishi, multi-instance takrori, listener event tartibi — eski engine bilan
   **kuzatilishi mumkin bir xil natija** (shadow-run).

> **O'lchov:** har qatlam uchun compat = "sxemani deploy + ishga tushir → eski va yangi engine bir xil token yo'li,
> bir xil connector chaqiruvlari, bir xil o'zgaruvchi natijasi." Farq = compat-bug.

---

## 3. Yangi engine parser arxitekturasi (plan 13 §3.1 aniqlashtiriladi)

Plan 13'dagi `ProcessDefinitionParser` abstraktsiyasini compat uchun aniqlashtiramiz:

```java
public interface ProcessDefinitionParser {
    boolean supports(SourceFormat fmt);
    ParseResult parse(byte[] source);   // ParseResult = ProcessDefinition + List<CompatWarning>
}
class CamundaCompatParser implements ProcessDefinitionParser { ... }  // eski dialektni AYNAN takrorlaydi
class NativeFormatParser  implements ProcessDefinitionParser { ... }  // kelajakdagi o'z formatimiz
```

- `CamundaCompatParser` — eski `BpmnService.import*` mantig'ining **toza, testlangan** ko'chirmasi (getBean yo'q,
  god-class yo'q, lekin **bir xil o'qish qoidalari**). Camunda BPMN Model API'ni xuddi eski kabi XML-reader sifatida
  ishlatadi (yoki standart BPMN 2.0 XSD + camunda extension — bir xil natija bersa).
- **ParseResult.warnings** — qamrab olinmagan har element/atribut uchun `CompatWarning{elementId, type, reason}`.
  Bu — "minimal o'zgarish" o'lchovi: bo'sh warnings = 100% compat.
- Ichki model **superset/pivot** (plan 13): eski Camunda dialekti + kelajakdagi native format ikkalasini ifodalaydi.

---

## 4. Noaniqliklar va boshqa guruhdan yig'iladigan inventar (Phase 0 — MAJBURIY)

> **Kod bilan yopilganlar (§1 Verify natijalari):** connectorId tashuvchisi, event-definition to'plami
> (faqat message/timer), expression evaluator (SpEL + `isExprStr`), multi-instance qo'llab-quvvatlash. Bular endi
> inventar SAVOLI emas — mexanizm aniq. Qolgan ochiq punktlar quyida.

### 4.1 Bloklovchi (davom etishdan oldin javob kerak)
1. **Nested-property persistence (Variant A/B)** — bpms-new Faza 0 (§1.3). Bir dona test-deploy bilan hal qilinadi
   (kod o'qish bilan yopilmaydi — dev muhit/DB kerak). Compat parser buni aynan takrorlashi kerak.
2. **Boshqa guruh sxema fayllari** — ularning `.bpmn` fayllari (yoki hech bo'lmasa 3–5 vakil sxema: eng murakkabi,
   ko'p-imzo zanjirlisi, subprocess/pool/multi-instance ishlatgani). **Bularsiz compat qamrovining (2)-manbasi
   taxminiy qoladi.** Local repo'da faqat `processes/*.bpmn` (credit-conveyor) bor; hrms/labour sxemalari yo'q. Bu
   fayllar §1.4 (signal/error ishlatiladimi), §1.6 (qaysi `bpms.hrms/labour.*` chaqiriladi) va §4.2 savollariga ham
   **avtomatik javob** beradi.
3. **`labour` domeni + `Signature*` moduli** — **boshqa domen** (HR), bizniki emas (§1.6). Local repo'da yo'q. Bizning
   script-compat scope'imizga kirmaydi; arxitektura esa domen-plugin script-namespace'ni qo'llab-quvvatlashi kifoya
   (yadro `labour`/`hrms`ni bilmaydi). Faqat agar ular sxemasini **shu** engine'da yuritmoqchi bo'lsak — o'sha domen
   o'z namespace + connector'ini plug qilishi kerak (alohida qaror).

### 4.2 Aniqlashtirish (parser/scope dizaynini o'zgartiradi)
4. **"Plugin komponentlar" nima?** — foydalanuvchi tilida noaniq. Ehtimoliy ma'nolar (boshqa guruhdan tasdiqlash):
   (a) **Camunda Modeler element-template'lari** (JSON, connector/form palette) — bu deploy'ga ta'sir qilmaydi, faqat
   modeler UI; (b) **custom form field type'lar** (CUSTOM_TYPE — allaqachon qo'llanadi); (c) **Camunda engine plugin'lari**
   (`ProcessEnginePlugin`) — agar shunday bo'lsa, ular custom engine'da mavjud EMAS, alohida ko'chirish kerak; (d) BPMN
   `extensionElements` ichidagi o'z namespace'lari. **Har ma'no boshqa ish talab qiladi** — aniqlash muhim.
5. **CUSTOM_TYPE to'liq katalogi** — ground-truth §6'da 10+ tur faqat **nom bilan** (JSON namunasi yo'q). Har biriga
   `properties`/`validations` shakli kerak (parser ularni CUSTOM_TYPE sifatida yutadi, lekin nested bo'lsa §4.1(1)ga
   bog'liq). Bu ham §4.1(2) real sxemalardan qisman aniqlanadi.

### 4.3 Yig'ish usuli
Boshqa guruhga beriladigan **so'rov** shu §4.1–4.2 dan tuziladi (agar foydalanuvchi so'rasa, alohida qisqa
so'rovnoma-fayl chiqaraman). Eng samarali: **ularning 3–5 vakil `.bpmn` faylini olish** — ko'p savol shundan
avtomatik javob topadi (aniq qaysi element/extension/listener/form-type/multi-instance ishlatilgani real sxemadan
ko'rinadi).

---

## 5. Plan 12/13 bilan moslashtirish (coverage jadvalini tuzatish)

Plan 13 §14.3 (va plan 12 §5 MVP) quyidagilarni **Deferred / qamrov tashqarisida** degan — bu **compat uchun
noto'g'ri**, chunki eski engine ularni allaqachon qo'llaydi (kod bilan tasdiqlangan) va boshqa guruh ishlatishi mumkin:

| Element | 13 §14.3 hozir | Compat talab (14) |
|---|---|---|
| Pool/Lane (Collaboration) | Deferred (qamrov tashqarisida) | **Kamida import + lane-taqsimot** kerak (eski engine qo'llaydi). Ijro darajasi §4.1(2) inventarga bog'liq. |
| Boundary Event | Deferred (bosqichma-bosqich) | Element import qilinadi; definition faqat message/timer (§1.4). Boshqa guruh ishlatsa — **Supported**. |
| Intermediate throw/catch | Deferred | Xuddi shunday — definition message/timer bilan cheklangan (§1.4). |
| CallActivity | (jadvalda yo'q) | Eski engine qo'llaydi → **compat qamroviga qo'shilsin**. |
| **Multi-instance** | (jadvalda yo'q) | **Eski engine QO'LLAYDI** (sequential/parallel, cardinality/collection/completionCondition — §1.1). Ko'p-imzo zanjiri shuni ishlatishi kuchli ehtimol → **compat qamroviga**. |
| Task/execution listener (class/expression/script) | (aniq yo'q) | **Mexanizm majburiy** (event turi + SpEL ijro). Script-namespace esa **abstract SPI** — domen o'zinikini beradi, yadroda `hrms` hardcode YO'Q (§1.6). |
| Expression (SequenceFlow shart, listener) | "expression port" (umumiy) | Aniq: **SpEL** + `isExprStr` heuristikasi + `$`-vars/`bpms` bean kontekst (§1.5). Yangi engine shuni takrorlaydi. |

> **Qoida:** compat qamrovi "biz nimani qulay deb bilamiz" bilan emas, **"eski engine o'qiydi ∩ kimdir ishlatadi"**
> bilan belgilanadi (§0). §14.3 jadvali §4 inventaridan keyin **rasman yangilanadi**.

---

## 6. Bosqichli yetkazish (compat-first)

| Bosqich | Mazmun | Chiqish (artefakt) |
|---|---|---|
| **0 — Verify & inventory** | §4.1 bloklovchilar: nested-property test-deploy; boshqa guruh 3–5 vakil sxemasi; labour/signature manbasi; "plugin" ma'nosi | Tasdiqlangan compat-scope hujjati (§14.3 yangilanadi) |
| **1 — Compat corpus** | Yig'ilgan real sxemalar + credit-conveyor `processes/*.bpmn` = **regres-korpus**. Har biri uchun kutilgan token-yo'li (eski engine'dan) qayd qilinadi | `compat-corpus/` + oltin natijalar |
| **2 — CamundaCompatParser (sintaksis+struktura)** | Eski `import*` qoidalarini toza ko'chirish: element qamrovi (§1.1, multi-instance bilan), form (§1.3), connector `inputOutput` (§1.2), listener (§1.6). Har korpus fayli **warnings=0** bilan parse bo'lsin | Parser + ParseResult.warnings + parse-testlar |
| **3 — Expression evaluator parity** | **SpEL** + `isExprStr` heuristikasi + kontekst-populatsiya (`$`-vars/`bpms` bean/MapAccessor) + xato→false; characterization test bilan eski bilan **bir xil** natija | Evaluator + parity-testlar |
| **4 — Ijro semantikasi parity** | Gateway (default/parallel-join/inclusive-join), boundary/timer/message, subprocess/callActivity, **multi-instance** (seq/parallel), listener event tartibi + **domen-registered script namespace** (abstract SPI, `hrms` hardcode emas) | Ijro testlari (spec + eski-parity) |
| **5 — Shadow-run** | Real sxemalar (kamida ko'p-imzo + subprocess/pool bittadan) ikkala engine'da → token-yo'li, connector chaqiruvlari, o'zgaruvchi natijasini solishtirish | Shadow-run hisobot; farqlar = 0 yoki hujjatlangan |
| **6 — Rollout qoidasi** | Sxema-sxema ko'chirish; import'da warning bo'lsa **deploy bloklanadi** (jim compat-buzilish yo'q) | Migratsiya runbook |

---

## 7. Definition of Done

- [ ] **Joylashuv:** butun yangi kod `bpms/bpms-new-backend/`da; `bpms/liveScoring-bpms-backend/` **o'zgarmagan** (0 fayl diff).
- [ ] §4.1 bloklovchilar hal qilingan (nested-property varianti; ≥3 real sxema; labour/signature manbasi; "plugin" ma'nosi).
- [ ] `CamundaCompatParser` — eski engine element/extension qamrovini (§1, multi-instance + connector `inputOutput` + listener bilan) **to'liq** o'qiydi; qamrab olinmagan element → **aniq warning**.
- [ ] Regres-korpus (real sxemalar + credit-conveyor) **warnings=0** bilan parse bo'ladi.
- [ ] Expression evaluator **SpEL + `isExprStr` + kontekst + xato→false** bo'yicha eski bilan characterization-parity.
- [ ] Ijro parity: gateway/token, boundary/timer/message, subprocess/callActivity, **multi-instance**, listener hook (domen o'z script-namespace'ini SPI orqali beradi — yadroda `hrms` hardcode yo'q).
- [ ] Shadow-run: kamida 1 ko'p-imzo + 1 subprocess/pool sxemasi — yangi ≡ eski natija.
- [ ] Plan 13 §14.3 coverage jadvali **inventar asosida yangilangan** (Deferred'lar qayta baholangan; multi-instance qo'shilgan).
- [ ] "Minimal o'zgarish" o'lchandi: qaysi sxemalar 0 o'zgarish, qaysilari nechta o'zgarish talab qildi — hujjatlangan.

## 8. Ochiq savollar (qaror kerak)

1. **Compat darajasi:** "0 o'zgarish" (yangi parser eski dialektni 100% yutadi) — vs — "kichik migratsiya skripti bilan
   normalize qilish" (masalan barcha eski sxemani bir marta yangi konvensiyaga o'giradigan transformer). Tavsiya:
   **0 o'zgarishga intilish**, faqat obyektiv imkonsiz bo'lsa migratsiya-skript.
2. **Pool/Lane ijro darajasi:** import (tashkiliy metadata) yetarlimi, yoki collaboration'da message-flow bo'ylab haqiqiy
   protsesslararo korrelyatsiya kerakmi — §4.1(2) inventaridan aniqlanadi.
3. **"Plugin komponent" ma'nosi** (§4.2(4)) — tasdiqlanmaguncha parser dizayni yakunlanmaydi.
4. **Boshlash sharti** — plan 12/13 kabi: **v9 testga topshirilgach**. Faza 0 (inventar) esa hoziroq boshlanishi mumkin
   (kod yozmaydi, faqat ma'lumot yig'adi).

---

## 9. Bog'liq fayllar
- `plans/BPMS_OPTIMIZATION_CONTEXT.md` — umumiy kirish.
- `plans/12-bpms-new-engine.md`, `plans/13-bpms-platform-standalone.md` — yangi engine "qanday quriladi".
- `plans/bpms-new/01-ground-truth-facts.md`, `02-phase0-verify-persistence.md` — form/CUSTOM_TYPE tasdiqlangan faktlar + nested-property testi.
- Xotira: `bpms-rewrite-context`, `bpms-schema-compat` (hrms domeni, boshqa-guruh inventari, dialekt-faktlar).
```
