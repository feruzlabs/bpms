# Fix 22 — Exclusive gateway: shartsiz chiquvchi flow o'lik yo'l bo'lyapti (merge gateway buziladi)

> **Ish papkasi:** `bpms/bpms-new-backend/` (yangi versiya). Eski bpms READ-ONLY.

## Muammo (tasdiqlangan — real 4888)
`ExecutionEngine.run()` exclusive-gateway mantig'i:
```java
List<SequenceFlow> matched = outgoing.stream()
    .filter(f -> f.condition().isPresent() && expressions.evaluateLogic(f.condition().get().expression(), evalVars))
    .toList();
if (!matched.isEmpty()) outgoing = List.of(matched.getFirst());
else outgoing = outgoing.stream().filter(f -> f.id().equals(gateway.defaultFlowId())).toList();
```
Faqat (a) **sharti true** yoki (b) **`default` atributli** flow olinadi. **Shartsiz** (condition yo'q, default emas) flow **hech qachon** tanlanmaydi → `outgoing` bo'sh → `close(at)` → token gateway'da COMPLETED (o'lik yo'l).

**Real misol:** 4888'dagi `Gateway_0tgpe7b` — **merge exclusive gateway** (3 kiruvchi, 1 chiquvchi `Flow_1xqadt9`
shartsiz, `default` yo'q). Token shu yerda o'ladi (protses yarim yo'lda "COMPLETED"). Bu ko'p real sxemaga tegadi
(har merge gateway / shartsiz chiquvchi).

**Eski engine parity:** `FlowService.isAcceptedCondition` — `condition == null` → **true** (shartsiz flow doim o'tadi).

## Tuzatish (BPMN-to'g'ri + eski parity)
Exclusive gateway: avval **shartli** tarmoqlar (condition true) — exclusivlik uchun; hech biri mos kelmasa —
**fallback**: explicit `default` YOKI **shartsiz** flow (implicit default).
```java
if (node instanceof ExclusiveGatewayNode gateway) {
    List<SequenceFlow> conditional = outgoing.stream()
        .filter(f -> f.condition().isPresent()
                  && expressions.evaluateLogic(f.condition().get().expression(), evalVars))
        .toList();
    if (!conditional.isEmpty()) {
        outgoing = List.of(conditional.getFirst());                 // sharti true — birinchisi
    } else {
        SequenceFlow fallback = outgoing.stream()
                .filter(f -> f.id().equals(gateway.defaultFlowId())) // 1) explicit default
                .findFirst()
                .or(() -> outgoing.stream()
                        .filter(f -> f.condition().isEmpty())        // 2) shartsiz = implicit default
                        .findFirst())
                .orElse(null);
        outgoing = fallback == null ? List.of() : List.of(fallback); // hech nima -> haqiqiy o'lik yo'l
    }
}
```
- **Merge gateway** (1 shartsiz chiquvchi) → shartsiz flow olinadi → o'tadi. ✅
- **Branch gateway** (shartlar + default) → true shart, aks holda default. ✅
- Haqiqatan mos kelmasa (shart false + default/shartsiz yo'q) → o'lik yo'l (to'g'ri).

## Inclusive gateway ham
`InclusiveGatewayNode` uchun ham xuddi shu illat bo'lishi mumkin (shartsiz flow tashlanadi). Inclusive'da **hamma**
true shart olinadi; hech biri bo'lmasa default/shartsiz. Shu mantiqni inclusive'ga ham qo'llang (agar hozir shart
bo'yicha filtrlasa).

## Test (majburiy)
- **Merge gateway:** start → (task) → exclusiveGateway (1 shartsiz chiquvchi) → task → end → **COMPLETED end'da**
  (gateway'da EMAS). Regres: shartsiz chiquvchi o'lik yo'l bo'lmasin.
- **Branch + default:** shart true → true-tarmoq; hamma false → default.
- **4888** (real): `Gateway_0tgpe7b` endi `Activity_1ft8j53` ga o'tadi (gateway'da to'xtamaydi).

## DoD
- [ ] Exclusive gateway: shartsiz chiquvchi flow olinadi (implicit default); conditional avval, keyin default/shartsiz.
- [ ] Inclusive gateway ham shartsiz flow'ni tashlamaydi.
- [ ] Merge-gateway + branch+default testlari yashil; 4888 gateway'da o'lmaydi.
- [ ] Eski bpms 0 diff.

## Cursor topshiriq
```
@bpms-new-backend. @22-fix-gateway-unconditional-flow.md ni bajar — ExecutionEngine exclusive (va inclusive) gateway:
shartsiz chiquvchi flow implicit default sifatida olinsin (hozir o'lik yo'l). Test: merge-gateway + branch+default + 4888.
```
