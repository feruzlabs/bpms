# ADR-001 — Skript execution-listener + `bpms.*` API vs. explicit service-task

> **Status:** taklif (qaror kutilmoqda). **Kontekst:** `processes/regional_vacation.bpmn` (HR approval) ~yarmi
> inline JS skript-listener bilan boshqariladi. Yangi engine'ga o'tishда uni **1:1 ko'chirish** yoki
> **externalize qilish** — arxitektura qarori.

---

## 1. "Skript-listener + `bpms.*`" — bu nima?
BPMN elementига (userTask/manualTask) hayot-sikl hook'и ulanган:
```xml
<camunda:executionListener event="start">
  <camunda:script scriptFormat="js">
    bpms.labour.generateFile();
    var emp = bpms.getValue('_info_employee_');
    bpms.hrms.startLineStateStatus('ON_EMPLOYEE');
    bpms.hrms.startUserStateStatus('ON_EMPLOYEE', emp);
  </camunda:script>
</camunda:executionListener>
```
- **`executionListener`** — element `start`/`end`/`take` bo'lганда engine ishга tushирадиган kod.
- **`camunda:script` (js)** — inline **JavaScript**; engine ичидаги JS runtime (Nashorn/Rhino/GraalJS) bajаради.
- **`bpms.*`** — engine skript-kontekстига **inject qилинган host-obyekt** (Java facade). Yuzasi (regional_vacation'да):
  - `bpms.getValue(name)` / `bpms.setValueStr/Object(name,val)` — process o'zгарувчилари.
  - `bpms.hrms.startLineStateStatus / startUserStateStatus / completeLineStateStatus / completeUserStateStatus /
    failLineStateStatus / failUserStateStatus / initializeLineStateStatus / setRegistrationCode(...)` — tashqи
    **HRMS** tizимидаги approval holат-mashinаsини yangилайди.
  - `bpms.labour.generateFile()` — HR hujjат (buyruq/ariza) generatsiyasi.
  - `bpms.http.postRequest / getRequestObject(url, ...)` — ixtiyoriy HTTP chaqiruv.

Ya'ni har task boshланганда/tugаганда engine **sinxron** JS ishлатади, u tashqи tizimга HTTP yozади, fayl
generatsiya qилади, va process o'zгарувчиларини o'zгартиради. Business-logика **diagramма ичида** yashaydi.

## 2. Nima uchun bu anti-pattern (muammolar)
- **Testlab bo'lmайди:** inline JS unit-test'га tushmайди; faqat to'liq engine + HRMS ishga tushса sinалади.
- **Type-safe emas / runtime-only xato:** `bpms.getValue('typo')` → `null`, xato faqat ishлаётганда chиqади.
- **Kuzатиб bo'lmайди:** side-effect'lар (HTTP, fayl, state) modelда **ko'ринмайди** — BPMN diagrammага qараб nima
  bo'lишини bilmaysиz.
- **Qattiq bog'lanиш:** model bitta JS-runtime + ulkan `bpms.*` API'ga bog'liq; engine almashса, hammasи sинади.
- **Retry/incident yo'q:** listener ичидаги HTTP xato bo'lса — retry, timeout, dead-letter, incident yo'q; token
  o'rtада qолади.
- **Thread bloklаш:** listener sinxрон I/O — engine thread'ини ushлаб turadi.
- **Versiyalаш/review og'ир:** logика diagramма XML ичида, code-review'да ko'рinmaydi, git-diff shovqinли.

## 3. Best-practice qanday bo'ladi?
**Har side-effect'ни explicit BPMN element qил** — model deklaратив, logика tashqарида:

| Hozir (skript-listener) | Best-practice |
|---|---|
| start listener: `bpms.hrms.startLineStateStatus('ON_EMPLOYEE')` | Undan **oldin serviceTask** "Set state ON_EMPLOYEE" (connector `HrmsStateConnector`, input `state=ON_EMPLOYEE, phase=START`) |
| end listener: `completeLineStateStatus(...)` | userTask'дан **keyin serviceTask** "Complete state" (connector, `phase=COMPLETE`) |
| reject manualTask: `failLineStateStatus(...)` | reject tarmog'ида **serviceTask** "Fail state" → terminate |
| `bpms.labour.generateFile()` | serviceTask "Generate document" (connector `LabourDocConnector`) — retry/timeout bilan |
| `bpms.http.postRequest(url,...)` | serviceTask/**external-task** "Register in HRMS" (connector) — incident/retry bilan |
| `bpms.getValue/setValue` | I/O mapping (`camunda:inputOutput`) yoki ifoda — engine variable API |

Foydаsи: har qadam **ko'ринади** (diagrammада), **mustaqил test qилинади** (connector unit-test), **retry/incident**
bilan ishonchли, **observable** (execution_log), va **script-runtime kerak emas**.

> **Muhим bonus:** externalize qилинса, protses yangi engine'да **ancha tez** ishлайди — chunki serviceTask+connector'ни
> engine **allaqачон** yuрgизади (kredit korпусдаги kabi). Faqat userTask/manualTask/terminate (reja 31) qolади;
> **JS-runtime + butun `bpms.*` API'ni ko'chиriш SHART bo'lmайди**.

## 4. Zamonaviy BPMS'lар nima qилади?
- **Camunda 7:** execution-listener + inline script'ни **qo'llaйди** (bu protses shu davр uslуби) — lekin
  hujjатларида ham "og'ир logикани delegate/service'ga chиqаринг" deб tavsiya qилади.
- **Camunda 8 / Zeebe:** inline script/execution-listener'дан **voz kechади**; **job worker / connector**ни majбуr
  qилади — logика tashqарида, model deklaратив. Ya'ni sanoat **externalize** tomonга ketган.
- **Flowable/Activiti:** listener bor, lekin best-practice — `JavaDelegate`/`serviceTask`, inline script minimal.

## 5. Qaror variantlari
1. **Replicate (tez, legacy):** JS-runtime (GraalJS) + `bpms.*` facade'ni yangi engine'ga 1:1 port qил. Eski
   protseslar **o'zгарисsиз** yuradi, lekin legacy qarz ko'chади (test/observability muammосi qolади).
2. **Externalize (best-practice, ko'proq ish):** skript side-effect'lарни connector/service-task'га ko'chир;
   protsesларни refactor qил (yoki avtomат transformer yoz). Model tozаланади, engine soddaроq.
3. **Gibrid (tavsiya):** yangi protseslар **externalize** (best-practice majбуr); eski protseslар uchun **minimal
   skript-shim** (faqat `bpms.getValue/setValue` + oz sonли toza helper) — og'ир side-effect'lар (http/labour/hrms
   state) esa **serviceTask'ga** ko'chирилади. Ya'ni JS bor, lekin faqat sof, side-effect'siz ifoda uchun.

## 6. Tavsiya
**Gibrid (3)** — yangi ish best-practice, eski protseslар bosqичма-bosqич externalize. `regional_vacation.bpmn`
uchun **best-practice clone** tayyorланди (`docs/examples/regional_vacation_bestpractice.bpmn`) — skript-listener'lар
serviceTask+connector'ga ko'chирилган namuna sifatida.

## 7. Oqibatlar
- (+) Model observable, testlab bo'ladi, retry/incident, JS-runtime kamаяди.
- (−) Refactor ishи (yoki transformer); eski protseslар qайта deploy.
- (−) Connector'lар yozилиши kerak (HrmsStateConnector, LabourDocConnector, ...) — lekin SPI'да arzon, testlab bo'ladi.
