# Task 34 — Public API test protsesi (engine imkoniyatini tekshirish)

> **Ish papkasi:** `bpms/bpms-new-backend/`. Eski bpms READ-ONLY.
> **Maqsad:** engine'ni **real public API** (github.com/public-apis/public-apis) bilan **end-to-end** sinash —
> start-forma → serviceTask(HTTP) → gateway (API ma'lumotiga qarab qaror) → end. Bu 30 (start-forma), serviceTask+
> connector, input/output mapping, 22 (gateway) — hammasi birga ishlashini isbotlaydi.

---

## 0. Grounding (engine hozir buni ko'taradi)
- serviceTask `camunda:connector` (`connectorId` + `inputOutput`) — ijro qilinadi (kredit korpus kabi).
- Connector SPI: `Connector.execute(ConnectorContext) -> ConnectorResult.ok(outputs)`; `ConnectorContext` =
  `{businessKey, variables, inputs}`; outputs → o'zgaruvchilarga (EAV).
- Ro'yxat: `ConnectorProvider` (`@Component`) → `ConnectorRegistry` (id bo'yicha; dublikat id → xato).
- Ifoda dialekti: `$var` va `${var}`; gateway shart `$aboveThreshold == true` / `!= null` (korpus-tasdiqlangan).
- **Yetishmaydi:** generic HTTP connector yo'q (faqat domen creditConveyer) → §1'da yangi connector kerak.

## 1. Yangi connector — `http-json-get` (generic, qayta ishlatiladigan)
> Bitta yangi `ConnectorProvider` (`@Component`) `bpms-server/.../connector/`da. **Yangi bog'liqlik shart emas:**
> JDK `HttpClient` + mavjud Jackson.

**Spetsifikatsiya:**
- `id = "http-json-get"`.
- **inputs:**
  - `url` (majburiy) — to'liq GET URL; `${base}` kabi ifodalar engine tomonidan interpolatsiya qilinadi.
  - `resultPath` (ixtiyoriy) — JSON dot-path (masalan `rates.UZS`) → scalar `result`.
  - `threshold` (ixtiyoriy) — berilsa va `result` son bo'lsa → `aboveThreshold = result >= threshold`.
- **outputs (→ o'zgaruvchilar):** `httpStatus` (int), `result` (scalar), `aboveThreshold` (bool), `body` (string).
- **Xato:** non-2xx yoki timeout → `ConnectorResult.fail(...)` (engine → incident/FAILED).
- **Xavfsizlik (hedge):** agar `url` hali `${...}` bo'lsa (interpolatsiya bo'lmagan), `base` o'zgaruvchidan
  `open.er-api.com/v6/latest/{base}` URL'ni o'zi qursin (demo self-contained).
- Timeout: connect 10s, request 15s.
> **Eslatma:** `result` son bo'lsa EAV typing (reja 16 — decimal→long) to'g'ri ishlashini tekshiring.

## 2. Test protsesi — `exchange_rate_alert.bpmn` (valyuta kursi ogohlantiruvi)
> `docs/examples/`ga. Bank domeniga mos: kurs chegaradan oshsa — ogohlantirish.

**Struktura (node → flow):**
1. **startEvent** `camunda:formKey="exchange_rate_form"`, formData:
   - `base` (string, default `USD`), `target` (string, default `UZS`), `threshold` (long, default `12000`).
2. **serviceTask** "Fetch exchange rate" — `camunda:connector`:
   - `connectorId = http-json-get`
   - inputParameter `url = https://open.er-api.com/v6/latest/${base}`
   - inputParameter `resultPath = rates.${target}`
   - inputParameter `threshold = ${threshold}`
   - (outputlar `rate`←`result`, yoki to'g'ridan-to'g'ri `result`/`aboveThreshold` o'zgaruvchi sifatida)
3. **exclusiveGateway** "Above threshold?":
   - flow → `EndAlert` shart: `$aboveThreshold == true`
   - flow → `EndOk` **shartsiz (implicit default — reja 22)**
4. **endEvent** `EndAlert` ("ALERT: above threshold"), **endEvent** `EndOk` ("OK: below threshold").

**Nima sinaladi:** start-forma validatsiya (30) + serviceTask connector (real HTTP) + input interpolatsiya + output→o'zgaruvchi + gateway API ma'lumotiga qarab (22) + terminate/end.

## 3. Muqobil public API'lar (auth'siz — public-apis'dan)
Bir xil `http-json-get` bilan (faqat url + resultPath o'zgaradi):
- **Bitcoin narxi:** `https://api.coingecko.com/api/v3/simple/price?ids=bitcoin&vs_currencies=usd` · `resultPath=bitcoin.usd` · `threshold=50000`.
- **Cat fact:** `https://catfact.ninja/fact` · `resultPath=fact` (chegarasiz, bitta qiymat).
- **Advice:** `https://api.adviceslip.com/advice` · `resultPath=slip.advice`.
- **IP geo:** `http://ip-api.com/json` · `resultPath=country`.

## 4. Deploy va ishga tushirish (qo'lda tekshirish)
1. Connector faylni qo'shib, `in-process` rejimda ilovani ishga tushiring (sinxron — natija darhol).
2. `exchange_rate_alert.bpmn`ni deploy qiling (deploy API).
3. Instance start: `POST /api/v1/process-instances` `{ ref, variables:{ base:"USD", target:"UZS", threshold:12000 } }`.
4. Kuzating: `execution_log` (connector start/end), `token_variable` (`rate`≈12600, `aboveThreshold=true`),
   token yo'li (`execution_token_state`) → `EndAlert`da COMPLETED.
> **Kutilgan:** USD→UZS ≈ 12600 > 12000 → `aboveThreshold=true` → **EndAlert**, instance COMPLETED.
> Chegarani 20000 qilsangiz → **EndOk** (default). Chegara boshqasiga → default tarmoq (22 fix isboti).
> **Talab:** engine host'ida **outbound internet** bo'lishi kerak (open.er-api.com'ga chiqish).

## 5. DoD
- [x] `http-json-get` connector (generic, yangi bog'liqliksiz) — `ConnectorProvider @Component`, id unique.
- [x] `exchange_rate_alert.bpmn` — start-forma + serviceTask(connector) + gateway($aboveThreshold==true)+default + 2 end.
- [x] Deploy + start → real HTTP → `rate`/`aboveThreshold` o'zgaruvchi → to'g'ri end; `execution_log`da ko'rinadi.
- [x] Internet yo'q / API pastda bo'lsa → connector `fail` → incident/FAILED (jim yutilmaydi).
- [x] Eski bpms 0 diff.

## 6. Cursor topshirig'i
```
Ish papkasi: bpms/bpms-new-backend/. Eski bpms faqat o'qish uchun.

docs/plans/34-public-api-test-process.md ni bajar.
1) Generic connector http-json-get (bpms-server/.../connector/, ConnectorProvider @Component): inputs url/resultPath/
   threshold, outputs httpStatus/result/aboveThreshold/body; JDK HttpClient + mavjud Jackson (yangi bog'liqlik yo'q);
   non-2xx -> ConnectorResult.fail.
2) docs/examples/exchange_rate_alert.bpmn: startEvent(form: base=USD,target=UZS,threshold=12000) ->
   serviceTask connectorId=http-json-get (url=https://open.er-api.com/v6/latest/${base}, resultPath=rates.${target},
   threshold=${threshold}) -> exclusiveGateway ($aboveThreshold == true -> EndAlert; shartsiz -> EndOk).
3) Deploy + start (base=USD,target=UZS,threshold=12000) -> aboveThreshold=true -> EndAlert, COMPLETED bo'lishini
   ko'rsat (execution_log + token_variable + token yo'li). Internet kerak.
Avval connector SPI (Connector/ConnectorContext/ConnectorResult/ConnectorProvider) va serviceTask ijrosini o'qib,
rejani ayt, keyin yoz.
```
