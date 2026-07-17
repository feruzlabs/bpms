# Plan 18 — creditConveyer **v9** domenini yangi engine'ga ko'chirish (test uchun)

> **Maqsad:** eski `bpms/liveScoring-bpms-backend/.../domain/creditConveyer` ning **v9 qatlamini** (16 connector +
> HTTP client/auth/DTO/servis) yangi engine'ga (`bpms/bpms-new-backend/`) **Connector SPI** ko'rinishida ko'chirish —
> yangi engine real domen bilan ishlashini tekshirish uchun.
> **Bu reja Cursor tomonidan bajariladi.** Ish papkasi: `bpms/bpms-new-backend/`. Eski bpms **READ-ONLY** (faqat o'qib
> ko'chiriladi, o'zgartirilmaydi).
>
> **Nega v9:** v9 — eng yangi, **stateless, Basic-auth** qatlam (token jadvaliga yozmaydi), engine-ichki holatga eng
> kam bog'langan → ko'chirish eng toza. (v2..v8 keyin, kerak bo'lsa.)

---

## 0. Yangi modul
```
bpms-new-backend/
└── bpms-connectors-creditconveyer/     (YANGI modul)
    ├── pom.xml                          (bpms-spi, okhttp3, gson, lombok, jackson)
    └── src/main/java/com/bpms/connectors/creditconveyer/
        ├── http/ConveyorClientV9.java   (ko'chirma — deyarli o'zgarishsiz)
        ├── auth/{ConveyorAuthProvider,BpmsBasicAuthProvider}.java  (ko'chirma)
        ├── vo/ConveyorPathsV9.java       (ko'chirma)
        ├── dto/... (v8+v9 response DTO, ErrorResponseDTO)          (ko'chirma)
        ├── exceptions/... (API* exceptions)                        (ko'chirma)
        ├── service/...V9Service.java (16 servis)                   (ko'chirma, @Value config)
        ├── connector/...V9Connector.java (16 connector)            (QAYTA YOZILADI — SPI)
        ├── connector/support/Io.java     (input/output yordamchi)
        └── CreditConveyerConnectorProvider.java                    (ConnectorProvider @Component)
```
`bpms-server/pom.xml` ga `bpms-connectors-creditconveyer` dependency; root `pom.xml` ga `<module>`.

## 1. Ko'chirish xaritasi (eski → yangi)

| Eski (creditConveyer) | Yangi | Amal |
|---|---|---|
| `services/v9/http/ConveyorClientV9` | `http/ConveyorClientV9` | **Ko'chirma.** `FileHelper.getHttpClient()` o'rniga yangi `OkHttpClient` bean (timeout bilan). |
| `services/v9/auth/*` | `auth/*` | Ko'chirma (Basic auth, `creditconveyer.bpms.{user,pass}`). |
| `vo/ConveyorPathsV9` | `vo/ConveyorPathsV9` | Ko'chirma (o'zgarishsiz). |
| `dto/v9/*`, `dto/v8/*` (javob DTO), `dto/ErrorResponseDTO` | `dto/*` | Ko'chirma (gson `@SerializedName`, lombok `@Data`). Faqat v9 servislar ishlatadiganini oling. |
| `exceptions/API*` | `exceptions/*` | Ko'chirma. |
| `services/v9/*V9Service` (16) | `service/*V9Service` | Ko'chirma. `@Value("${creditconveyer.endpoint}")` qoladi. |
| `connectors/services/v9/*V9Connector` (16) | `connector/*V9Connector` | **QAYTA YOZILADI** (SPI, quyida §2). |
| `connectors/services/v9/support/ConnectorIo` | `connector/support/Io` | Kerak emas — SPI `ctx.inputs()`/`ConnectorResult.outputs()` beradi. |
| `@Component("XV9Connector")` | `Connector.id()` = **"XV9Connector"** | **AYNAN o'sha nom** — v9 BPMN `camunda:connectorId` o'zgarmasin (compat). |

**16 v9 connector id (o'zgarmaydi):** CalcPensionSumV9Connector, CheckKATMBanV9Connector, CloseKATMV9Connector,
CreateRequestAndClientV9Connector, DwhMastercardFeaturesV9Connector, GetClientInfoV9Connector,
GetScoringResultV9Connector, MasterCardScoreV9Connector, RefreshAccountHistoryV9Connector,
RefreshActiveAccountsV9Connector, RefreshIABSDataV9Connector, RefreshIIBV9Connector, RefreshKATM22V9Connector,
RefreshKATM77V9Connector, RefreshNPSV9Connector, StopListV9Connector.

## 2. Connector qayta yozish patterni (eski `execute(InstanceTokenState)` → SPI)

Eski I/O konvensiyasi (SAQLANADI):
- Input `token` → **qiymat** (baholanadi).
- Input `*VarSet` / `*VarSet`-turi (masalan `IsSuccessVarSet`, `ResponseVarSet`) → **chiqish o'zgaruvchisining NOMI** (literal
  satr). Eski `connectorIo.setVar(state, <o'sha nom>, value)` bilan yozardi.
- **Xato oqimi:** eski connector API xatosida token'ni **FAILED qilmaydi** — `IsSuccessVar=false` + `ErrorMsgVar=xabar`
  yozadi, oqim davom etadi (gateway `IsSuccess` bo'yicha yo'naltiradi). ⇒ Yangi connector ham **`ConnectorResult.ok(...)`**
  qaytaradi (`fail()` EMAS), flag'larni output'ga soladi.

Yangi engine mos kelishi:
- `ctx.inputs().get("token")` — allaqachon SpEL+isExprStr bilan baholangan (eski `expressionExecuteResultIgnore` bilan
  bir xil). `*VarSet` inputlari trigger-belgisiz literal → o'zgaruvchi nomi bo'lib qaytadi (to'g'ri).
- Chiqish: `out.put(<varName>, value)` → `ConnectorResult.ok(out)` → engine o'sha nomli o'zgaruvchilarni yozadi.

### To'liq shablon (GetScoringResultV9Connector — qolgan 15 shu andozada)
```java
final class GetScoringResultV9Connector implements Connector {
    private final GetScoreResultV9Service service;
    private final Gson gson;
    GetScoringResultV9Connector(GetScoreResultV9Service service, Gson gson) { this.service = service; this.gson = gson; }

    public String id() { return "GetScoringResultV9Connector"; }   // eski @Component nomi bilan AYNAN

    public ConnectorResult execute(ConnectorContext ctx) {
        Map<String,Object> in = ctx.inputs();
        String token          = Io.str(in.get("token"));            // qiymat
        String isSuccessVar   = Io.name(in.get("IsSuccessVarSet")); // chiqish o'zgaruvchisi NOMI
        String errorMsgVar    = Io.name(in.get("ErrorMsgVarSet"));
        String responseVar    = Io.name(in.get("ResponseVarSet"));
        String scoringMsgVar  = Io.name(in.get("ScoringMessage"));
        String avgIncomeVar   = Io.name(in.get("ClientAvgIncomeSum"));
        String creditSumVar   = Io.name(in.get("ClientCreditSumVarSet"));
        String statusVar      = Io.name(in.get("ClientStatusVarSet"));

        Map<String,Object> out = new HashMap<>();
        try {
            var response = service.refresh(token);                  // ConveyorClientV9.get(...) -> PHP
            Io.put(out, responseVar,  response);
            Io.put(out, isSuccessVar, true);
            Io.put(out, creditSumVar, gson.toJson(response.getLoans()));
            Io.put(out, avgIncomeVar, (long) response.getClientMonthlyIncome());
            Io.put(out, scoringMsgVar, response.getMessage());
            Io.put(out, statusVar,    response.getStatus());
        } catch (Exception e) {
            Io.put(out, isSuccessVar, false);
            Io.put(out, errorMsgVar,  e.getMessage());
        }
        return ConnectorResult.ok(out);                             // fail() EMAS — eski xatti-harakat
    }
}
```
`Io` yordamchi:
```java
final class Io {
    static String str(Object o){ return o == null ? "" : String.valueOf(o); }
    static String name(Object o){ return o == null ? null : String.valueOf(o); }   // chiqish o'zgaruvchi nomi
    static void put(Map<String,Object> m, String name, Object v){ if (name != null && !name.isBlank()) m.put(name, v); }
}
```

### Provider (connectorlar Spring bean emas — servislar inject qilinadi)
```java
@Component
public class CreditConveyerConnectorProvider implements ConnectorProvider {
    private final List<Connector> connectors;
    public CreditConveyerConnectorProvider(GetScoreResultV9Service scoreSvc, /* ...16 servis... */ Gson gson) {
        connectors = List.of(
            new GetScoringResultV9Connector(scoreSvc, gson)
            /* , new CreateRequestAndClientV9Connector(...), ... qolgan 15 */
        );
    }
    public Collection<Connector> connectors() { return connectors; }
}
```
> `Gson` va `OkHttpClient` bean'lari (`@Bean`) modul config'ida e'lon qilinadi (Boot ularni avtomat bermaydi).

## 3. Config (`application.yml`)
```yaml
creditconveyer:
  endpoint: ${CREDITCONVEYER_ENDPOINT:http://localhost:8081}   # PHP app (Yii2)
  bpms:
    user: ${BPMS_USER:bpms}
    pass: ${BPMS_PASS:bpms}
```
docker-compose `app` env'iga `CREDITCONVEYER_ENDPOINT/BPMS_USER/BPMS_PASS` qo'shiladi.

## 4. Test (ishlashini tekshirish) — MUHIM

Korpusdagi `open_card_credit_scoring_v9000000.bpmn` aslida **v5/v7** connectorlarni ishlatadi (v9 connectorlarni EMAS),
shuning uchun v9 connectorlar uchun **alohida test-BPMN** kerak: `docs/examples/credit-v9-smoke.bpmn` (shu rejaga ilova).

**Ikki test rejimi:**
- **(A) Stub (o'z-ichida, tavsiya):** `bpms-server` testida **WireMock** (yoki oddiy stub controller) `creditconveyer.endpoint`
  ni imitatsiya qiladi — `GET /v9/mobile/request/score/{token}` uchun tayyor JSON qaytaradi. Shunda tashqi PHP kerak emas.
  Deploy `credit-v9-smoke.bpmn` → start `{token:"T1", ...}` → connector stub'ni chaqiradi → `isOk=true` + skoring
  o'zgaruvchilari → gateway "approved" yo'liga → COMPLETED.
- **(B) Real dev endpoint:** `CREDITCONVEYER_ENDPOINT` real dev PHP'ga + `BPMS_USER/PASS` real qiymatga → haqiqiy v9
  skoring. (Tarmoq/credential mavjud bo'lsa.)

**credit-v9-smoke.bpmn** (ilova, v9 connector id bilan):
```
start → serviceTask(GetScoringResultV9Connector, inputs: token=#root['token'], IsSuccessVarSet="isOk", ResponseVarSet="scoreResp", ...)
      → exclusiveGateway(#root['isOk'] == true ? approved : rejected) → end
```
> `token=#root['token']` (`'` trigger); `IsSuccessVarSet="isOk"` (literal → o'zgaruvchi nomi). Gateway sharti
> `#root['isOk'] == true` (`'` trigger).

## 5. Guardrail / qoidalar
- Kod faqat `bpms/bpms-new-backend/` (yangi modul). Eski bpms **faqat o'qib ko'chiriladi**, o'zgartirilmaydi.
- `connectorId` = eski @Component nomi bilan **aynan** (BPMN o'zgarmasin).
- Xato oqimi eski kabi (`ok()` + flag, `fail()` emas) — gateway semantikasi buzilmasin.
- Real PHP javob shakli o'zgarmaydi (bir xil DTO/paths).
- Bu modul **credit-conveyor domeniga xos** — yadro/serverga hardcode qilinmaydi (izolyatsiya; boshqa domen o'zinikini beradi).

## 6. Definition of Done
- [ ] `bpms-connectors-creditconveyer` moduli: client/auth/paths/dto/exceptions/servis **ko'chirilgan**, kompilyatsiya.
- [ ] 16 v9 connector SPI ko'rinishida, id = eski nom; `CreditConveyerConnectorProvider` ro'yxatga qo'shadi.
- [ ] `Gson` + `OkHttpClient` (timeout) bean; `creditconveyer.*` config.
- [ ] `credit-v9-smoke.bpmn` + **WireMock stub** bilan: deploy → start → connector chaqiriladi → o'zgaruvchilar → gateway → COMPLETED (o'z-ichida test yashil).
- [ ] (ixtiyoriy) real dev endpoint bilan bitta v9 connector jonli tekshirildi.
- [ ] Eski bpms 0 fayl diff. Stack Java 21 / Boot 3.5.14.

## 7. Cursor'ga topshiriq (namuna)
```
Yangi task. Ish papkasi: @bpms-new-backend.
@18-port-creditconveyer-v9.md ni bajar — creditConveyer v9 domenini yangi engine'ga ko'chir.
Avval FAZA: modul + infra ko'chirma (client/auth/paths/dto/exceptions/servis) + GetScoringResultV9Connector shabloni
+ CreditConveyerConnectorProvider + credit-v9-smoke.bpmn + WireMock stub testi. 
Qolgan 15 connector'ni shu andozada keyin. connectorId = eski @Component nomi (aynan). 
Eski bpms READ-ONLY (faqat o'qib ko'chir). Avval fayllarni o'qib, planni ayt, keyin yoz.
```
