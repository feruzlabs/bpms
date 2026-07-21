# Task 23 — `execution_token_state` modeli: before/after-execute + listener trace

> **Ish papkasi:** `bpms/bpms-new-backend/`. Eski bpms READ-ONLY.
> Bu reja `execution_token_history` nomini **`execution_token_state`**ga o'zgartiradi va har bir node
> ijrosini **debug qilinadigan** qilib modellaydi: BEFORE_EXECUTE / AFTER_EXECUTE listener eventlari +
> step statusining ACTIVE → COMPLETED/FAILED lifecycle'i.

## 0. Muammo
Hozirgi `execution_token_history` — sof append-only (bitta qator, hech qachon update bo'lmaydi). Bu ikki narsani
bermaydi:
1. **Listenerlar** — har bir BPMN komponentda (`serviceTask`, `userTask`, gateway, event) `executionListener`
   bo'lishi mumkin (`event="start"` / `event="end"`, Camunda dialekti). Bir nodeda **bir nechta** listener bo'lishi
   mumkin — ularning har biri alohida ishlaydi, alohida xato berishi mumkin. Bu hozir hech qayerda yozilmaydi.
2. **Step statusi** — "bu qadam hali ham ishlayaptimi yoki tugadimi" degan savolga append-only tarix javob
   bermaydi (faqat "bo'ldi" deb yozadi, "hali ketyapti" holatini ko'rsatmaydi).

## 1. `execution_token_state` (rename: `execution_token_history` → shu)
**Naqsh — Camunda'ning o'zining `ACT_HI_ACTINST` (historic activity instance) bilan bir xil:** node'ga kirganda
qator **INSERT** qilinadi (`status=ACTIVE`), node ijrosi tugaganda **shu qatorning o'zi UPDATE** qilinadi
(`status=COMPLETED/FAILED`, `exited_at` to'ldiriladi). Append-only emas, insert-then-update — lekin har bir
**tashrif** (loop bo'lsa ham) alohida qator (`sequence_no` bilan), shuning uchun "qanday yo'l bosdi" ma'nosi
yo'qolmaydi.

| ustun | tur | izoh |
|---|---|---|
| `id` | UUID PK | |
| `token_id` | FK → `execution_token.id` | |
| `instance_id` | FK (denorm) | join'siz tez so'rov uchun |
| `node_id` | text | BPMN elementId |
| `node_type` | text | `serviceTask`/`userTask`/`exclusiveGateway`/... (modeldan denorm — debug uchun qayta parse shart emas) |
| `sequence_no` | int | token bo'yicha monoton o'suvchi — loop'da bir xil `node_id` qayta tashrif buyursa ham tartib buzilmaydi |
| `status` | enum | `ACTIVE` (kirganda) → `COMPLETED` \| `FAILED` \| `CANCELED` (chiqqanda) |
| `entered_at` | timestamp | BEFORE_EXECUTE fire bo'lgan payt |
| `exited_at` | timestamp, null bo'lishi mumkin | AFTER_EXECUTE tugagan payt; `status=ACTIVE` bo'lsa hali `null` |
| `duration_ms` | int, nullable | `exited_at - entered_at`, chiqishda hisoblanadi (perf debug uchun qulay) |
| `error_message` | text, nullable | `status=FAILED` bo'lsa |

Indekslar: `(token_id, sequence_no)`, `(instance_id, node_id)`.

## 2. `execution_listener_log` (YANGI jadval)
Har bir **alohida listener chaqiruvi** — chunki bitta nodeda bir nechta listener bo'lishi mumkin va ularning
har biri mustaqil debug birligi.

| ustun | tur | izoh |
|---|---|---|
| `id` | UUID PK | |
| `token_state_id` | FK → `execution_token_state.id` | aynan qaysi step-tashrifga tegishli |
| `instance_id` | FK (denorm) | |
| `node_id` | text (denorm) | |
| `phase` | enum | `BEFORE_EXECUTE` \| `AFTER_EXECUTE` (Camunda `event="start"`/`"end"` ekvivalenti) |
| `listener_index` | int | bitta phase'da bir nechta listener bo'lsa, tartib |
| `listener_type` | enum | `CLASS` \| `EXPRESSION` \| `DELEGATE_EXPRESSION` (eski dialekt parity) |
| `listener_ref` | text | class nomi yoki expression matni — "aynan qaysi listener" savoliga javob |
| `status` | enum | `SUCCESS` \| `FAILED` |
| `started_at` / `ended_at` | timestamp | |
| `error_message` | text, nullable | |

## 3. Engine oqimi (`ExecutionEngine`, node ijrosi atrofida)
```java
// 1) token node'ga keldi
TokenStateRecord state = tokenState.insertActive(tokenId, instanceId, nodeId, nodeType, nextSeq); // ACTIVE, entered_at=now

// 2) BEFORE_EXECUTE listenerlar (node.startListeners())
for (var l : node.startListeners()) {
    var log = listenerLog.insertRunning(state.id(), BEFORE_EXECUTE, l);
    try { l.execute(ctx); listenerLog.markSuccess(log.id()); }
    catch (Exception e) { listenerLog.markFailed(log.id(), e); throw e; } // listener xatosi -> node FAILED (propagate)
}

// 3) asosiy node logikasi (connector/gateway/...) — mavjud execution_log o'zgarmaydi

// 4) AFTER_EXECUTE listenerlar (node.endListeners()) — xuddi shu naqsh, phase=AFTER_EXECUTE

// 5) step yakuni
tokenState.complete(state.id(), COMPLETED /* yoki FAILED */, now());
```
> **Xato siyosati:** listener xato bersa — node ijrosi `FAILED` deb hisoblanadi (jim yutib yubormaslik) — bu
> production BPMS'da standart, chunki listenerlar ko'pincha business-critical (masalan, audit yozish, tashqi
> tizimga bildirish).

## 4. Debug hikoyasi (nima uchun bu yetarli)
- **"Hozir qayerda"** → `execution_token` (o'zgarmaydi).
- **"Qanday yo'l bosdi, har bir qadam qachon boshlandi/tugadi, hali ketyaptimi"** → `execution_token_state`
  (`status`, `entered_at`/`exited_at`, `sequence_no` bilan to'liq path).
- **"Shu qadamda listenerlar qanday ishladi, qaysi biri xato berdi"** → `execution_listener_log`
  (`token_state_id` orqali step bilan bog'langan).
- **"Nega bunday natija chiqdi" (connector/gateway tafsiloti)** → `execution_log` (o'zgarmaydi).

## 5. Migratsiya
- Flyway migration: `execution_token_history` → rename `execution_token_state` + yangi ustunlar
  (`node_type`, `sequence_no`, `exited_at`, `duration_ms`) + yangi jadval `execution_listener_log`.
- Kod: barcha `TokenHistoryPort`/`ExecutionTokenHistory*` nomlanishlarni `TokenStatePort`/`ExecutionTokenState*`
  ga o'zgartirish (grep bilan topib almashtirish).

## 6. DoD
- [ ] `execution_token_history` → `execution_token_state` (rename, yangi ustunlar: `node_type`, `sequence_no`,
      `exited_at`, `duration_ms`).
- [ ] Node'ga kirganda `ACTIVE` insert, chiqishda **shu qatorning o'zi** `COMPLETED`/`FAILED`ga update (append emas,
      insert-then-update; har tashrif — alohida qator, `sequence_no` bilan).
- [ ] `execution_listener_log` (yangi jadval) — BEFORE_EXECUTE/AFTER_EXECUTE, har listener alohida qator,
      `token_state_id` orqali bog'langan.
- [ ] Listener xato → node `FAILED` (jim yutilmaydi, propagate).
- [ ] Test: 1 node, 2 ta start-listener + 1 end-listener → 3 ta `execution_listener_log` qatori + 1 ta
      `execution_token_state` qatori (`ACTIVE`→`COMPLETED`). Listener xato stsenariysi → `FAILED` + `error_message`.
- [ ] Eski bpms 0 diff.

## 7. Claude Code (terminal) topshiriq
```
Ish papkasi: bpms/bpms-new-backend/. Eski bpms (bpms/liveScoring-bpms-backend/) faqat o'qish uchun.

/tmp/23-execution-token-state-model.md ni bajar: execution_token_history -> execution_token_state (rename +
yangi ustunlar), yangi execution_listener_log jadvali, ExecutionEngine node ijrosida BEFORE_EXECUTE/AFTER_EXECUTE
listener fire qilish va step statusini ACTIVE->COMPLETED/FAILED qilib yopish.

Avval hozirgi ExecutionEngine.run() va TokenHistory bilan bog'liq fayllarni o'qib, qanday o'zgartirish
rejalashtirayotganingni menga qisqa ayt, keyin yoz. Testlar bilan tasdiqla.
```
