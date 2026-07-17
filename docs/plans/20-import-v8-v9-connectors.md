# Plan 20 — v8 + v9 barcha connector/servislarni yangi engine'ga import qilish (eski + yangi protsesni sinash)

> **Maqsad:** eski `creditConveyer` domenining **v6/v7 + v8 (Bearer)** va **v9 (Basic)** connector/servislarini yangi
> engine'ga (`bpms/bpms-new-backend/`) ko'chirib, real protseslarni sinash: **`TUNE_CREDIT_REQUEST_4888`** (V6/V7),
> **`TUNE_CREDIT_REQUEST_6004`** (V8), **`TUNE_CREDIT_REQUEST_7000`** (V8+V9).
> **Ish papkasi:** `bpms/bpms-new-backend/`. Eski bpms **READ-ONLY** (faqat o'qib ko'chiriladi).
> **Bog'liq:** plan 18 (v9 pattern — bu reja uni v8 bilan kengaytiradi).

---

## 0. Savolga javob — holat (state) saqlanadimi? **HA, hammasi.** (kod bilan tasdiqlandi)

| Holat | Jadval | Qanday |
|---|---|---|
| Instance | `process_instance` | start=RUNNING → userTask=WAITING → COMPLETED/FAILED. Har o'tishda `instances.save`. |
| Token | `execution_token` | Har node o'tishida `tokens.save(current_node_id + status)`. Status: ACTIVE (yuryapti), WAITING (userTask), WAITING_JOB (async serviceTask), COMPLETED, FAILED. |
| **UserTask** | `user_task` | Token userTask'ga yetsa: token=WAITING, instance=WAITING, `user_task` yozuvi (token_id+node_id) yaratiladi, engine **to'xtaydi**. `POST /{id}/complete` → o'zgaruvchi yoziladi, task closed, token **davom etadi**. |
| **Service task job** (async) | `job` | `enqueueServiceTask` → `job` (PENDING) yoziladi, token WAITING_JOB, engine to'xtaydi. Consumer job'ni oladi → `executeConnector` → `continueAfterServiceTask`. |
| O'zgaruvchi | `token_variable` | EAV (§ plan 15/16). |
| Ijro logi | `execution_log` | plan 19 (`ExecutionLogPort` interfeysi allaqachon bor — `NoOpExecutionLogPort` default). |

⇒ Protses **pauza + resume** (userTask/job) qila oladi va **restartga chidamli** (hamma holat DB'da). Bu talab bajarilgan.

---

## 1. Import doirasi + inventar

| Versiya | Auth | Connector soni | Servis soni | Infra |
|---|---|---|---|---|
| **v9** | Basic (`bpms.user/pass`) | 16 | 16 | `ConveyorClientV9` + `BpmsBasicAuthProvider` (plan 18) |
| **v8** | **Bearer** (`login/passport`, token-kesh) | 13 | 14 | `GetTokenService` (Bearer) + `RefreshService`/`GetResultService` + `ConveyorPaths`(v8) |
| **v6 + v7_1** | **Bearer** (v8 bilan bir xil) | ~11 (4888 uchun) | v6/v7_1 servislar | `RefreshService`/`GetResultService` + `ConveyorPaths` + **`CreditConveyorHelper` subset** + `BISetStateConnector` |

- **v9 connector id (16):** CalcPensionSumV9Connector, CheckKATMBanV9Connector, CloseKATMV9Connector,
  CreateRequestAndClientV9Connector, DwhMastercardFeaturesV9Connector, GetClientInfoV9Connector,
  GetScoringResultV9Connector, MasterCardScoreV9Connector, RefreshAccountHistoryV9Connector,
  RefreshActiveAccountsV9Connector, RefreshIABSDataV9Connector, RefreshIIBV9Connector, RefreshKATM22V9Connector,
  RefreshKATM77V9Connector, RefreshNPSV9Connector, StopListV9Connector.
- **v8 connector (13):** `connectors/services/v8/` + `.../v8/refresh/` ostidagilar. **id = aynan `@Component("...")` qiymati**
  (Cursor grep qilib oladi — fayl nomi bilan bir xil bo'lmasligi mumkin, masalan `CalcPensionSumConnector`).
- **v6/v7 connector (`TUNE_CREDIT_REQUEST_4888` uchun — 11 ta):** `BISetStateConnector`, `CloseKATMV6Connector`,
  `CreateRequestAndClientV6Connector`, `GetClientInfoV6Connector`, `GetResponseByTokenForRequestAndClientV6Connector`,
  `RefreshActiveAccountsV6Connector`, `RefreshIABSDataV6Connector`, `RefreshKATM22V6Connector`, `RefreshKATM77V6Connector`,
  `RefreshNPSV6Connector`, `TuneSentMobileAPIV7Connector`. Manba: `connectors/services/v6/`, `.../v7_1/`, va
  `connectors/BISetStateConnector.java` (+ `BISetStateCommonConnector.java`).
  - ⚠️ **v6/v7 murakkabroq:** `CreateRequestAndClientV6Connector` va yana 1 connector **`CreditConveyorHelper`ni ishlatadi**
    (43KB, engine-coupled — request payload'ini ko'p o'zgaruvchidan quradi). **Faqat kerakli metodlarini** ko'chiring
    (butun helperni emas), `InstanceTokenStateService`/`expressionExecute` → yangi `ctx.variables()`/SpEL bilan almashtiring.
  - `BISetStateConnector` (4888'da 35× ishlatiladi) — holat/state o'rnatuvchi umumiy connector; uni ham ko'chiring.

**Muhim:** v8 connectorlar `CreditConveyorHelper` (43KB) ga **bog'liq EMAS** (0 ta havola) — u import qilinmaydi. Ular
`RefreshService`/`GetResultService`/`GetTokenService`/`ConveyorPaths`(v8) + `expressionExecute` + `InstanceTokenStateService`
input/output uchun ishlatadi — bularning input/output qismi yangi SPI (`ctx.inputs()`/`ConnectorResult.outputs()`) bilan
almashadi (v9 pattern bilan bir xil).

## 2. Infra ko'chirish

### 2.1 v9 (Basic) — plan 18 (o'zgarishsiz)
`ConveyorClientV9` + `ConveyorAuthProvider`/`BpmsBasicAuthProvider` + `ConveyorPathsV9` + v9 DTO/servis.

### 2.2 v8 (Bearer) — YANGI
- **`GetTokenService` → `BearerTokenAuthProvider implements ConveyorAuthProvider`:** token-kesh + refresh
  (`/v1/auth/default/login-app`, `creditconveyer.login/passport`). `authorizationHeader()` = `"Bearer " + token`.
- **`RefreshService`, `GetResultService`, `CreateRequestService`** (generic okhttp GET/POST, Bearer) — ko'chirma.
  `FileHelper.getHttpClient()` → yangi `OkHttpClient` bean.
- **`ConveyorPaths`** (v8 yo'llari) + 14 v8 servis (`GetScoreResultV8Service`, `CheckKATMBanV8Service`, refresh/... ) — ko'chirma.
- v8/v5 DTO (javob), exceptionlar — kerakligini ko'chir.

> Ikki `ConveyorAuthProvider` bean (`Basic` v9, `Bearer` v8) — har versiya klienti to'g'ri auth'ni oladi (masalan
> `@Qualifier` yoki har klientga mos provider inject).

## 3. Connector qayta yozish patterni (v8 ham v9 ham bir xil — plan 18 §2)
- Eski `execute(InstanceTokenState)` + `getInputVar/setVar` → `Connector.execute(ConnectorContext)` + `ctx.inputs()` /
  `ConnectorResult.ok(outputs)`.
- **`*VarSet` inputlari = chiqish o'zgaruvchisi NOMI** (literal); xatoda **`ok()` + `IsSuccess=false`/`ErrorMsg`** (token
  FAILED emas). `connectorId` = eski `@Component` nomi bilan **aynan** (BPMN o'zgarmasin).
- Namuna: plan 18 §2 (`GetScoringResultV9Connector`) — v8 uchun aynan shu andoza, faqat servis v8'niki (`GetScoreResultV8Service`).

## 4. Provider
`CreditConveyerConnectorProvider` (yoki ikkita: `...V8Provider`, `...V9Provider`) — barcha 13+16 connector'ni servislar
bilan quradi va `connectors()` da qaytaradi. Idlar global noyob (versiya suffiksi bilan allaqachon shunday).

## 5. Config
```yaml
creditconveyer:
  endpoint: ${CREDITCONVEYER_ENDPOINT:...}   # real dev PHP (yetib bo'ladigan; host uchun host.docker.internal)
  bpms: { user: ${BPMS_USER:...}, pass: ${BPMS_PASS:...} }   # v9 Basic
  login: ${CC_LOGIN:...}                                      # v8 Bearer
  passport: ${CC_PASSPORT:...}
```

## 6. Test rejasi (aniq — korpusda tayyor sxemalar)

| Sxema (compat-corpus) | Connector versiyasi | Rol |
|---|---|---|
| **`TUNE_CREDIT_REQUEST_4888.bpmn`** | **V6 + V7** (11 conn) | Eski protses (V6/V7 + BISetState + helper) |
| **`TUNE_CREDIT_REQUEST_6004.bpmn`** | **V8** (14 conn) | "Eski ishlab turgan protses" |
| **`TUNE_CREDIT_REQUEST_7000.bpmn`** | **V8 + V9** (16 conn) | "Yangi protses" |

Qadamlar (har biri uchun): deploy → start (real `token`/businessKey + kerakli o'zgaruvchilar) → engine ijro etadi →
`GET /process-instances/{id}` + `.../logs` (plan 19) bilan token-yo'li, connector natijalari, xatolarni ko'rish.

**Endpoint ikki variant:**
- **(A) Real dev PHP** — `CREDITCONVEYER_ENDPOINT` + Bearer(`login/passport`) + Basic(`user/pass`) real → haqiqiy
  skoring. (Konteynerdan yetib bo'lishi kerak — `host.docker.internal` yoki real URL; "Network unreachable" bo'lmasin.)
- **(B) Stub (WireMock)** — endpoint imitatsiya, kanonik JSON → engine oqimi/parity tashqi bog'liqliksiz sinaladi.

**Parity (ixtiyoriy, kuchli):** o'sha sxema + o'sha token'ni **eski engine**da ham ishlatib, token-yo'li/natijalarni
solishtirish (shadow-run) — yangi ≡ eski.

## 7. MUHIM caveat — boshqa versiyalar
Bu reja: **v6/v7 (4888), v8 (6004), v8+v9 (7000)**. Qolgan korpus sxemalari **v2/v3/v5** connectorlarini ishlatadi
(masalan `open_card..._v9000000` = V5/V7 — V5 qismi kirmaydi, `OFT_CREDIT_REQUEST_1` = V5, `..._v4_3610` = V2/V3).
Ular hozir doirada **emas** — kerak bo'lsa mos versiya connectorlarini alohida import qilasiz.
> Har sxema uchun: `grep -oE "<camunda:connectorId>[^<]+" <fayl>` bilan kerakli connectorlar ro'yxatini oling.

## 8. Bosqichlar (Cursor uchun)
1. **v9** (plan 18) — 16 connector + infra (agar bajarilmagan bo'lsa).
2. **Bearer infra** — `BearerTokenAuthProvider` (GetTokenService) + `RefreshService`/`GetResultService`/`ConveyorPaths` + OkHttp bean + config. (v8, v6, v7 birga ishlatadi.)
3. **v8 servis + 13 connector** — SPI rewrite; providerga qo'shish. → **Test 6004 (V8)**.
4. **v6/v7 servis + 11 connector** (+ `BISetState` + `CreditConveyorHelper` kerakli subseti) — SPI rewrite. → **Test 4888 (V6/V7)**.
5. **Test 7000 (V8+V9)** — ikkala versiya bir sxemada.
> Tavsiya tartib: v9 → v8 (6004) → v6/v7 (4888) → 7000. Har fazadan keyin test/logs.

## 9. Guardrail / DoD
- Kod faqat `bpms/bpms-new-backend/` (`bpms-connectors-creditconveyer` moduli). Eski bpms **faqat o'qib ko'chiriladi**, 0 fayl diff.
- `connectorId` = eski `@Component` nomi bilan aynan; `*VarSet` + `ok()`-on-error semantikasi saqlangan.
- Ikki auth (Basic v9 / Bearer v8) to'g'ri ishlaydi.
- [ ] 16 v9 + 13 v8 + 11 v6/v7 (+ BISetState) connector SPI ko'rinishida ro'yxatdan o'tgan.
- [ ] Bearer auth (token-kesh/refresh) ishlaydi (v6/v7/v8 uchun).
- [ ] `CreditConveyorHelper`ning faqat kerakli subseti ko'chirilgan (butun 43KB emas), yangi SPI'ga moslangan.
- [ ] `TUNE_CREDIT_REQUEST_4888` (V6/V7) deploy+start → engine ijro etadi; logs to'liq.
- [ ] `TUNE_CREDIT_REQUEST_6004` (V8) deploy+start → engine ijro etadi (real yoki stub); logs to'liq.
- [ ] `TUNE_CREDIT_REQUEST_7000` (V8+V9) deploy+start → ishlaydi.
- [ ] (ixtiyoriy) 6004 shadow-run: yangi ≡ eski.
- [ ] Stack Java 21 / Boot 3.5.14; eski bpms 0 diff.

## 10. Cursor'ga topshiriq (namuna)
```
Yangi task. Ish papkasi: @bpms-new-backend. @20-import-v8-v9-connectors.md ni bajar.
Faza 2-3: v8 infra (BearerTokenAuthProvider/GetTokenService + RefreshService/GetResultService + ConveyorPaths(v8) + config)
+ 13 v8 servis/connector (SPI rewrite, id = eski @Component nomi) + providerga qo'shish.
Keyin: TUNE_CREDIT_REQUEST_6004 (V8) deploy+start test (stub yoki real endpoint) + logs.
CreditConveyorHelper'ni import qilma (kerak emas). Eski bpms READ-ONLY. Avval fayllarni o'qib, planni ayt, keyin yoz.
```
