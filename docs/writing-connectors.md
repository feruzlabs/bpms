# Connector yozish qo'llanmasi (bpms-new-backend)

> Connector = **serviceTask**ni bajaruvchi kod bloki. BPMN'dagi `serviceTask` `camunda:connectorId` orqali bitta
> connector'ga bog'lanadi; engine token o'sha task'ga yetganda connector'ni chaqiradi. Ishlab chiqarish connectorlari
> **o'z domen modulida** bo'lishi kerak (yadro/serverga hardcode qilinmaydi — izolyatsiya). Demo/test connectorlari:
> `bpms-server/.../connector/` (masalan `ExampleConnectorProvider`).

## 1. SPI kontrakti (`com.bpms.spi.connector`)

```java
public interface Connector {
    String id();                                  // BPMN camunda:connectorId bilan AYNAN teng
    ConnectorResult execute(ConnectorContext ctx);
    default ConnectorDescriptor describe() { ... } // ixtiyoriy: katalog/validatsiya uchun
}

public record ConnectorContext(
    String businessKey,
    Map<String,Object> variables,   // instance'ning HAMMA o'zgaruvchisi (o'qish uchun)
    Map<String,Object> inputs       // shu task'ning baholangan camunda:inputParameter'lari
) {}

public record ConnectorResult(boolean success, Map<String,Object> outputs, String errorMessage) {
    static ConnectorResult ok(Map<String,Object> outputs);   // muvaffaqiyat + chiqish o'zgaruvchilari
    static ConnectorResult fail(String errorMessage);        // xato (token FAILED bo'ladi)
}

public record ConnectorDescriptor(String id, String description,
                                  List<ConnectorInputDesc> inputs, List<String> outputs);
public record ConnectorInputDesc(String name, boolean required, String type, String description);
```

## 2. Input / output oqimi (engine qanday ishlaydi)

1. Token `serviceTask`ga yetadi.
2. Engine har `camunda:inputParameter` **qiymatini SpEL bilan baholaydi** (instance o'zgaruvchilariga qarab) →
   `ctx.inputs()` map'iga soladi.
3. `connector.execute(ctx)` chaqiriladi. `ctx.inputs()` — baholangan inputlar; `ctx.variables()` — hamma o'zgaruvchi.
4. `ConnectorResult.ok(outputs)` qaytsa — **`outputs` map'i instance o'zgaruvchilariga yoziladi** (kalit = o'zgaruvchi
   nomi). `fail(msg)` yoki exception — token **FAILED**, instance FAILED.
5. Keyingi sequenceFlow'lar (gateway shartlari) yangi o'zgaruvchilarni ko'radi.

> **Chiqish nomlari:** connector qaytargan `outputs` **kalitlari to'g'ridan-to'g'ri o'zgaruvchi nomi** bo'ladi.
> Ya'ni `out.put("score", 72)` → `score` o'zgaruvchisi. (Xohlagan nomni bering.)

## 3. ⚠️ MUHIM: `isExprStr` quirk (eski engine parity)

Input va shart ifodalari SpEL bilan baholanadi, **lekin faqat** satr quyidagi belgilardan **birini** o'z ichiga olsa:
`+  -  *  /  .  ,  $  '  "`. Aks holda satr **literal** (o'zi) sifatida qaytadi — baholanmaydi.

| Ifoda | Natija |
|---|---|
| `amount` | ❌ literal `"amount"` (belgi yo'q) — o'zgaruvchi olinmaydi! |
| `amount.doubleValue()` | ✅ baholanadi (`.` bor) → son |
| `amount + 0` | ✅ baholanadi (`+` bor) |
| `#root['amount']` | ✅ baholanadi (`'` bor) → qiymat o'zgartirilmasdan |
| `score.intValue() >= 50` | ✅ shart sifatida ishlaydi (`.` bor) |
| `approved == true` | ❌ literal (belgi yo'q) → shart doim `false`! |

**Qoidalar:**
- O'zgaruvchini connector'ga uzatish: `amount.doubleValue()` (raqam), `name.toString()` (satr), yoki har qanday
  tipni o'zgartirmasdan — `#root['amount']`.
- Gateway shartlari: har doim belgi bo'lsin — `score.intValue() >= 50`, `#root['approved'] == true`.
- Xato/parse muvaffaqiyatsiz → `null` → shart uchun `false` (jim). Bu ham eski engine bilan bir xil.

## 4. Registratsiya

Connector'lar `ConnectorProvider` orqali beriladi; `ConnectorRegistry` startup'da hammasini **id bo'yicha** yig'adi
(dublikat id → **startup xatosi**, `getBean` yo'q — type-safe).

```java
@Component
public class MyDomainConnectorProvider implements ConnectorProvider {
    public Collection<Connector> connectors() {
        return List.of(new GetScoringResultConnector(), new CreateRequestConnector());
    }
}
```
> Bir nechta provider bo'lishi mumkin (har domen o'zinikini beradi). Idlar global noyob bo'lsin
> (masalan `creditConveyer:GetScoringResult` — namespace bilan).

## 5. BPMN binding (serviceTask ↔ connector)

```xml
<bpmn:serviceTask id="score" name="Score">
  <bpmn:extensionElements>
    <camunda:connector>
      <camunda:connectorId>demo-credit-score</camunda:connectorId>   <!-- = connector.id() -->
      <camunda:inputOutput>
        <camunda:inputParameter name="amount">amount.doubleValue()</camunda:inputParameter>
        <camunda:inputParameter name="monthlyIncome">monthlyIncome.doubleValue()</camunda:inputParameter>
      </camunda:inputOutput>
    </camunda:connector>
  </bpmn:extensionElements>
</bpmn:serviceTask>
```
Namespace: `xmlns:camunda="http://camunda.org/schema/1.0/bpmn"`. `connectorId` ro'yxatdan o'tgan `id()` bilan aynan
teng bo'lishi shart (noto'g'ri bo'lsa — startup/ijro xatosi).

## 6. To'liq misol (connector kodi)

```java
static final class CreditScoreMockConnector implements Connector {
    public String id() { return "demo-credit-score"; }
    public ConnectorResult execute(ConnectorContext ctx) {
        BigDecimal amount = num(ctx.inputs().get("amount")).max(BigDecimal.ONE);
        BigDecimal income = num(ctx.inputs().get("monthlyIncome"));
        BigDecimal annual = income.multiply(BigDecimal.valueOf(12));
        int score = annual.signum() <= 0 ? 0 : Math.min(100,
            annual.multiply(BigDecimal.valueOf(100)).divide(amount, RoundingMode.HALF_UP).intValue());
        Map<String,Object> out = new HashMap<>();
        out.put("score", score);
        out.put("approved", score >= 50);
        return ConnectorResult.ok(out);
    }
    public ConnectorDescriptor describe() {
        return new ConnectorDescriptor(id(), "Soxta kredit-skoring (demo)",
            List.of(new ConnectorInputDesc("amount", true, "number", "so'ralgan summa"),
                    new ConnectorInputDesc("monthlyIncome", true, "number", "oylik daromad")),
            List.of("score", "approved"));
    }
}
```

## 7. Qoidalar / best practices
- **id noyob** va barqaror (BPMN'da ishlatiladi). Namespace bilan (`domain:name`) to'qnashuvni oldini oladi.
- **Idempotent** bo'ling — async (RabbitMQ) rejimda job qayta ishlanishi mumkin (retry). Bir xil input → bir xil natija,
  ikki marta ishlasa zarar bo'lmasin.
- **Timeout** — tashqi HTTP chaqiruvida har doim timeout qo'ying (osilib qolmasin). Bloklovchi I/O Virtual Thread'da ijro etiladi (engine hal qiladi).
- **Xato:** kutilgan biznes-xato → `ConnectorResult.fail("...")`; kutilmagan → exception (ikkalasi token'ni FAILED qiladi).
- **Stateless** — connector holat saqlamasin (bir instance ko'p token uchun ishlaydi).
- **Izolyatsiya** — connector o'z domenida; yadroga domen-mantiq hardcode qilinmaydi.

## 8. Demo connectorlar (test uchun — `ExampleConnectorProvider`)

| id | inputs | outputs | maqsad |
|---|---|---|---|
| `noop` | — | — | hech nima (DemoConnectorProvider) |
| `echo` | har qanday | inputlar aynan | inputni outputga (DemoConnectorProvider) |
| `demo-sum` | `a`, `b` (raqam) | `sum` | ikki raqamni qo'shish |
| `demo-credit-score` | `amount`, `monthlyIncome` | `score`(int), `approved`(bool) | soxta skoring |
| `demo-set-var` | `name`, `value` | `<name>: value` | qiymat/doimiy yozish |
| `demo-fail` | — | — | har doim fail (xato oqimi testi) |

Demo protsess: `docs/examples/demo-credit-scoring.bpmn`.

## 9. Ishga tushirish (uchidan-uchiga sinov)

```bash
# 1) deploy
curl -X POST localhost:8090/api/v1/process-definitions \
     -H "Content-Type: application/xml" \
     --data-binary @docs/examples/demo-credit-scoring.bpmn
# -> {definitionId, key: "demo-credit-scoring", version}

# 2) start (approved bo'ladigan qiymatlar)
curl -X POST localhost:8090/api/v1/process-instances \
     -H "Content-Type: application/json" \
     -d '{"definitionKey":"demo-credit-scoring","businessKey":"REQ-1",
          "variables":{"amount":5000000,"monthlyIncome":4000000}}'
# -> instance COMPLETED; score/approved o'zgaruvchilari hisoblangan; token "approved" end'da

# 3) rad etiladigan qiymatlar
curl -X POST localhost:8090/api/v1/process-instances \
     -H "Content-Type: application/json" \
     -d '{"definitionKey":"demo-credit-scoring","businessKey":"REQ-2",
          "variables":{"amount":200000000,"monthlyIncome":4000000}}'
# -> token "rejected" end'da
```
> Async (RabbitMQ, Faza G) yoqilgan bo'lsa: `start` `RUNNING` qaytishi va connector'dan keyin `COMPLETED` bo'lishi
> mumkin — `GET /api/v1/process-instances/{id}` bilan tekshiring.
