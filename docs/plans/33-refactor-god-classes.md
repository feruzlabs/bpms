# Task 33 — Refactoring: god-class'larni bo'lish (ExecutionEngine, JpaPersistenceAdapter)

> **Ish papkasi:** `bpms/bpms-new-backend/`. Eski bpms READ-ONLY.
> **Maqsad:** ikkita god-class'ni bo'lib, keyingi element/feature qo'shishni **toza va testlab bo'ladigan** qilish.
> **Xatti-harakat o'zgarmaydi (behavior-preserving refactor)** — barcha mavjud test yashil qolishi shart.

---

## 0. Aniqlangan god-class'lar (LOC)
| Fayl | LOC | Muammo |
|---|---|---|
| `bpms-engine/.../ExecutionEngine.java` | **793** | Barcha node dispatch + gateway + token lifecycle + terminate bitta klassда; 7 element qo'shsak 1200+ |
| `bpms-persistence-jpa/.../JpaPersistenceAdapter.java` | **685** | Barcha persistence port (instance/token/variable/job/task/log/subscription) bitta klассда |
| `bpms-parser-camunda/.../ElementMapper.java` | 315 | O'rta — kuzatilsin |
| `bpms-server/.../ProcessEngineService.java` | 253 | O'rta — kuzatilsin |

---

## 1. `ExecutionEngine` → **NodeBehavior strategiyasi** (asosiy)
Hozir: bitta `run()` ичида ketма-кет `if (node instanceof StartEventNode) {...} else if (ServiceTaskNode) {...}` —
har node turи inline. Refactor: **har node turi uchun bitta handler**.

```java
public interface NodeBehavior<N extends FlowNode> {
    Class<N> nodeType();
    /** Node ijrosi: token'ni WAIT qiladi, advance qiladi yoki tugatadi. */
    NodeResult execute(N node, ExecutionContext ctx);   // ctx: token, vars, ports, expressions, clock
}
```
- Handler'lар: `StartEventBehavior`, `EndEventBehavior` (terminate ичida), `ServiceTaskBehavior`, `ScriptTaskBehavior`,
  `UserTaskBehavior`, `ManualTaskBehavior`, `ExclusiveGatewayBehavior`, `InclusiveGatewayBehavior`,
  `ParallelGatewayBehavior`.
- `ExecutionEngine` **yupqa dispatcher** bo'ladi: `Map<Class<?>, NodeBehavior>` (Spring bean'lар avtomат yig'иlади) →
  `behaviors.get(node.getClass()).execute(node, ctx)`. Loop/step-budjet/cooperative-stop/token-state/listener trace
  **engine'да markazий** qoladi; faqat **node-maxsус** logика handler'ga ko'chади.
- Foydаsи: (a) har node **mustaqil test**; (b) yangi element = **yangi handler klass** (god-class o'sмайди); (c) reja
  32'даги 7 element toza qo'шilади; (d) `run()` o'qилади, 793 → ~200 qatorга tushади.

> **Behavior-preserving:** logика **ko'chирилади**, o'zгартирилмайди. Har handler'ning natижаsи hozirги inline blok
> bilan **bir xил** bo'lиши kerak — mavjud testlар (gateway/userTask/terminate/serviceTask) **yashил qolsin**.
> Gateway shartсиз-flow (reja 22), token_state trace (reja 23), terminate (reja 31) — hammasи handler'larга ko'chади.

## 2. `JpaPersistenceAdapter` → **port bo'yicha bo'lish**
Hozir: bitta klass barcha port'ни implement qилади. Refactor: **har aggregate uchun alohида adapter**:
- `InstanceJpaAdapter` (InstancePort), `TokenJpaAdapter` (TokenPort + TokenStatePort), `VariableJpaAdapter`
  (VariablePort + history), `JobJpaAdapter` (JobPort), `UserTaskJpaAdapter` (UserTaskPort),
  `ExecutionLogJpaAdapter` (ExecutionLogPort), `EventSubscriptionJpaAdapter` (EventSubscriptionPort).
- Har biri o'z port interfeysини implement qилади (SPI o'zgarмайди — faqat implementatsiya bo'lиниши).
- Umumий SQL/mapping yordamчиlар — `JpaSupport`/`RowMappers` (kichик shared util).
- Foydаsи: har adapter kичик, testlab bo'ladi; yangi jadval → yangi adapter (685 qator god-class emas).

## 3. Ikkinchi darajali (kuzatilsin, majбур emas)
- `ElementMapper` (315) — element→node mapping o'sса, `NodeMapper` per-tur (yoki registry) bilan bo'lish.
- `ProcessEngineService` (253) — start/complete/terminate/suspend alohида service'larга ajratish (agar o'sса).

## 4. Bajarish tartibi (MUHIM)
> **Tavsiya: bu reja (33) reja 32'дан OLDIN** (yoki hech bo'lмаса ExecutionEngine NodeBehavior qismи). Sabab:
> 793-qатор god-class'ga 7 element qo'shish uni portlаb yuboradi; avval strategiyа refactorи → keyin element'lар
> **handler** sifatида toza tushади. Aks holда keyin ikki barobar ish (refactor + qayta yozish).

Bosqич:
1. `NodeBehavior` interfeys + `ExecutionContext` + `NodeResult` (yangi, engine ичида).
2. Mavjud 9 node blokини handler klass'larга **ko'chир** (bittalab, har ko'chirishдан keyin test yashил).
3. `run()`ни dispatcher qил (map bo'yicha); loop/guard/trace markazда qoladi.
4. `JpaPersistenceAdapter`ни port bo'yicha adapter'larга bo'l.
5. Barcha mavjud test yashил — **0 behavior o'zgариши**.

## 5. DoD
- [x] `NodeBehavior` strategiyа; `ExecutionEngine.run()` yupqa dispatcher (~200 qator); 9 handler klass.
- [ ] `JpaPersistenceAdapter` port bo'yicha adapter'larга bo'linган (har biri o'z port'и).
- [x] **Barcha mavjud test yashил** (behavior-preserving — 0 funksional o'zгариш).
- [x] Yangi element (reja 32) endi **handler** sifatида qo'шilади (god-class o'sмайди).
- [x] Eski bpms 0 diff.

## 6. Cursor topshirig'i
```
Ish papkasi: bpms/bpms-new-backend/. Eski bpms faqat o'qish uchun.

docs/plans/33-refactor-god-classes.md ni bajar — BEHAVIOR-PRESERVING refactor (xatti-harakat o'zgarmaydi,
barcha test yashil qolsin).

1) ExecutionEngine (793 qator): NodeBehavior interfeysi + ExecutionContext + per-node handler klasslar
   (StartEvent/EndEvent/ServiceTask/ScriptTask/UserTask/ManualTask/Exclusive/Inclusive/ParallelGateway).
   run() yupqa dispatcher (Map<Class,NodeBehavior>); loop/step-budjet/cooperative-stop/token_state trace/listener
   markazda qoladi, faqat node-maxsus logika handler'ga ko'chadi. Har blokni bittalab ko'chir, har qadamda test yashil.
2) JpaPersistenceAdapter (685): port bo'yicha alohida adapter (Instance/Token/Variable/Job/UserTask/Log/EventSubscription),
   umumiy mapping JpaSupport util. SPI port interfeyslari O'ZGARMAYDI.

Avval ExecutionEngine.run() va JpaPersistenceAdapter ni to'liq o'qib, ko'chirish rejasini menga ayt, keyin bosqichma-
bosqich qil. Har bosqichdan keyin butun test to'plami yashil bo'lsin (0 funksional o'zgarish). Eski bpms 0 diff.
```
