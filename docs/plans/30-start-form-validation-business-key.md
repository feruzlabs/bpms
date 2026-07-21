# Task 30 — Start-forma validatsiyasi + business key (`businessProcessKeyVar`) — reja 21 §2.1 yakunlash

> **Ish papkasi:** `bpms/bpms-new-backend/`. Eski bpms READ-ONLY (parity manbasi).
> **Nega:** korpusdagi **10/10 sxema** start-forma bilan boshlanadi (`camunda:formKey` + `camunda:formData`).
> Bu reja 21 §2.1'da **atayin qoldirilgan** qism edi. 22 (gateway) + 28 (timer) + shu 30 bo'lsa —
> `TUNE_CREDIT_REQUEST_4888/6004/7000` **to'liq end-to-end** ishlaydi (barcha real sxema yuradi).

---

## 0. Grounding — real forma strukturasi (4888)
```xml
<bpmn2:startEvent id="StartEvent_tvjo8qg1" camunda:formKey="tune_credit_request_start_form">
  <bpmn2:extensionElements>
    <camunda:formData>
      <camunda:formField id="request_id_tune_credit_request_start_form" label="Request ID" type="string" />
      <camunda:formField id="pinfl_tune_credit_request_start_form"     label="PINFL"      type="string" />
      <camunda:formField id="passport_tune_credit_request_start_form"  label="Passport"   type="string" />
      <camunda:formField id="product_code_abs_credit_request_start_form" type="string" />
      <camunda:formField id="without_report_to_tune" type="boolean" defaultValue="false" />
      <camunda:formField id="amount_tune_credit_request_start_form"  type="string" />
      <camunda:formField id="percent_..." type="string" /> <!-- term/grace ham -->
    </camunda:formData>
  </bpmn2:extensionElements>
</bpmn2:startEvent>
```
- Maydonlar: `id`, `label`, `type` (`string`/`boolean`/…), `defaultValue`.
- **Forma BPMN ichida embed** (tashqi `.form` fayl emas) → **yangi jadval SHART EMAS**; parser modelga chiqaradi,
  DefinitionRegistry cache'ida turadi.

## 1. Parity manbasi (eski kod — AVVAL O'QILSIN)
Yangi kod shu semantikani takrorlaydi:
- `com/bpmn/entity/Form.java` — `businessProcessKeyVar` maydoni.
- `com/bpmn/service/FormService.java` (~209–236, 304+) — business key form atributidan
  (`formDataModel.getAttributeValue("businessKey")` → `form.setBusinessProcessKeyVar(...)`).
- `com/bpmn/service/ValidationService.java` — maydon validatsiyasi (type/required).
- `com/bpmn/service/BpmExecutionService.java` — `createInstanceToken` / start semantikasi, `validateStartFormSubmit`.
> **Muhim:** `businessProcessKeyVar` qanday aniqlanishini (qaysi formField business key ekanini) **eski koddan
> aniq o'qing**. Kuzatilgan xatti-harakat: 4888'da business key = `request_id_..._start_form` maydonining qiymati
> (reja 21 §2.1 eslatmasi). Aniq qoidani parity uchun eski koddan tasdiqlang.

## 2. Parser (parser-camunda)
`startEvent`dan formani modelga chiqarish:
```
StartEventNode {
  id, formKey,                       // "tune_credit_request_start_form"
  formFields: List<FormField> { id, label, type, defaultValue, required?, constraints? },
  businessKeyVar                     // §1 qoidasi bo'yicha (yoki null)
}
```
> `camunda:constraint` (required, minlength…) bo'lsa o'qing; korpusda asosan `type` + `defaultValue`.

## 3. Validatsiya (`ValidationService` parity)
`start(input)` da, forma bo'lsa:
1. Har `formField` uchun `input`dan qiymat olinadi; yo'q bo'lsa `defaultValue` (bor bo'lsa).
2. **Type coercion:** `string`→String, `boolean`→Boolean (`without_report_to_tune`), `long`/`double`/`date`→mos tur.
   Xato type → validatsiya xatosi.
3. `required` (yoki constraint) buzilsa → **HTTP 422** + qaysi maydon(lar) xato (field→message).
4. Muvaffaqiyat → koerце qilingan qiymatlar `token_variable` (EAV) ga boshlang'ich qiymat sifatida.
> Forma **yo'q** protsessda bu qadam **skip** (mavjud async start buzilmaydi).

## 4. Business key (`resolveBusinessKey`)
```
bk = (model.businessKeyVar != null && input[businessKeyVar] != null)
     ? String(input[businessKeyVar])     // forma businessProcessKeyVar'dan (masalan request_id...)
     : requestBusinessKey                // aks holda POST'dagi businessKey
```
4888: `businessKeyVar = request_id_tune_credit_request_start_form` → `bk = input[shu]`.
> `process_instance.business_key` shu `bk` bilan yoziladi (unique `(tenant_id, business_key)` — v3).

## 5. `ProcessEngineService.start` ga ulash (reja 21 §2.1)
Enqueue'dan **oldin**:
```java
validateStartForm(model, input);                          // §3 — xato -> 422, instance yaratilmaydi
String bk = resolveBusinessKey(model, input, businessKey); // §4
// keyin mavjud oqim: instance + token_variable(koerце qilingan input) + start token + PROCESS_START job -> enqueue
```
Tartib: validatsiya → business key → DB (instance/vars/token/job) → enqueue → 202. Forma yo'q → validatsiya/bk skip.

## 6. API / xato formati
- `POST /api/v1/process-instances` `{ definitionRef, businessKey?, variables:{...} }`.
- Validatsiya xatosi → **422** `{ "errors": [ {"field":"request_id_...","message":"required"} ] }`.
- Muvaffaqiyat → **202** `{ id, status:"RUNNING", businessKey }`.

## 7. Test (majburiy)
- **4888/6004/7000 start-forma:** to'g'ri `input` (request_id, pinfl, passport, amount…) → 202, `business_key =
  request_id qiymati`, `token_variable`da koerце qilingan qiymatlar (`without_report_to_tune=false` boolean).
- **Required buzilishi:** `request_id` yo'q → 422, instance yaratilmaydi.
- **Type coercion:** `without_report_to_tune` berilmasa → default `false` (boolean); berilса `"true"`→`true`.
- **Business key fallback:** businessKeyVar'siz forma → request'dagi `businessKey` ishlatiladi.
- **Forma yo'q sxema:** validatsiya skip, mavjud async start ishlaydi (regres).
- **End-to-end (katta bosqich):** 4888 start-forma → skoring/timer-polling (28) → gateway (22) → BISetState →
  end → COMPLETED.

## 8. DoD
- [ ] Parser start-forma (formKey + formField: id/label/type/defaultValue/constraint) + businessKeyVar'ni modelga chiqaradi.
- [ ] Validatsiya (type coercion + required/default) — `ValidationService` parity; xato → 422.
- [ ] `resolveBusinessKey` — forma businessProcessKeyVar'dan (aks holda request bk); parity eski koddan tasdiqlangan.
- [ ] `ProcessEngineService.start` — enqueue'dan oldin validate + bk; forma yo'q → skip.
- [ ] Yangi jadval YO'Q (forma BPMN'dan parse, cache'da).
- [ ] 4888/6004/7000 start-forma testlari + end-to-end yashil; eski bpms 0 diff.

## 9. Cursor topshirig'i — quyida (alohida).
