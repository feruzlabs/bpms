# Task 29 — DoD gap-close (22, 25, 27) + README yangilash

> **Ish papkasi:** `bpms/bpms-new-backend/`. Eski bpms READ-ONLY.
> **Maqsad:** 22/25/27'dagi DoD bo'shliqlarini yopish — **yangi feature yo'q, asosan test + kichik ulanish +
> README holati**. 28 (timer)dan mustaqil, parallel ketadi (faqat §3 node-revisit guard 28 bilan bog'liq).

---

## 1. Reja 22 — Gateway (test bo'shligi)
Kod bor (exclusive + inclusive + merge/default). Yetishmaydi — **faqat testlar**:
- [ ] **Inclusive gateway alohida test:** bir nechta shart bir vaqtda true → **hamma** true tarmoq olinadi;
      hech biri true bo'lmasa → default/shartsiz. (Exclusive'dan farqi — bittasi emas, hammasi.)
- [ ] **4888 regressiya:** `Gateway_0tgpe7b` (merge exclusive, 3 kiruvchi, 1 shartsiz chiquvchi `Flow_1xqadt9`)
      → token `Activity_1ft8j53`ga o'tadi, gateway'da o'lmaydi. Real `TUNE_CREDIT_REQUEST_4888.bpmn` bilan.

## 2. Reja 25 — Trigger testi
DDL/partition/circular FK/seed bor. Yetishmaydi:
- [ ] **Trigger revision testi (avtomatik):** `token_variable` INSERT (`value_text='620'`, revision=0) → UPDATE
      (`'655'`) → assert: `token_variable_history`da **1 qator** (`old_value_text='620'`, `revision=0`),
      `token_variable.revision` **0→1**, `token_variable.value_text='655'`. Qiymat o'zgarmagan UPDATE → history'ga
      **yozilmaydi** (negativ holat ham tekshirilsin).
- ERD/PNG — **DoD-blocker EMAS** (`docs/bpms-schema.dbml` dbdiagram.io'da render bo'ladi). Xohlansa keyin.

## 3. Reja 27 — Test + guard ulanish
TERMINATE/SUSPEND/RESUME/cascade/ack-drop/step-budjet/REST bor. Yetishmaydi:
- [ ] **Cascade terminate testi:** root + 2-3 bola instance (`parent_instance_id`) → `terminate?cascade=true` →
      hammasi TERMINATED, token CANCELED, `event_subscription` 0.
- [ ] **Idempotent terminate testi:** ikki marta terminate → ikkinchisi no-op (xato yo'q, holat o'zgarmaydi).
- [ ] **⚠️ Node-revisit guard AKTIVLIGI (28 bilan bog'liq):** `execution_token_state` bo'yicha bir xil
      `(instance_id, node_id)` qayta-tashrif soni threshold (masalan 1000)dan oshsa → incident `LOOP_DETECTED` +
      SUSPEND. **Bu 28'dagi timer-polling loop (Iteration→timer→gateway→loop) chiqmasa cheksiz aylanmasin uchun
      SHART.** Test: ataylab chiqmaydigan loop → cap → incident. (step-budjet CPU-spin uchun; revisit-guard timer
      loop uchun — ikkovi ham bo'lsin.)
- [ ] **Spawn/depth guard — DORMANT qoldiriladi (hujjatlashtirilsin):** `checkSpawnDepthBeforeStart` hozir
      ulanmaydi, chunki call-activity/subprocess spawn nuqtasi **hali yo'q**. Kodda `// TODO: wire on call-activity
      (plan XX)` izohi + README/plan 27'da "dormant until call-activity" deb belgilansin. (Sun'iy ulash yo'q.)

## 4. README yangilash
`docs/plans/README.md` holatlarini kodga moslash:
- 22 → 🔧 qisman (kod bor; §1 testlardan keyin ✅).
- 23 → ✅.
- 24 → ✅ (XML). 25 → 🔧 qisman (§2 triggerdan keyin ✅). 26 → ✅.
- 27 → 🔧 qisman (§3 testlardan keyin ✅; spawn-guard dormant izohi bilan).
- 29 → shu reja (qator qo'shilsin).

## 5. DoD
- [ ] 22: inclusive test + 4888 regressiya yashil.
- [ ] 25: trigger revision testi (pozitiv + negativ) yashil.
- [ ] 27: cascade + idempotent terminate testlari; node-revisit guard aktив + testi; spawn-guard dormant izohlangan.
- [ ] README holatlari to'g'ri (22/25/27 → aniq holat; 29 qo'shildi).
- [ ] Eski bpms 0 diff.

## 6. Cursor topshirig'i — quyida (alohida).
