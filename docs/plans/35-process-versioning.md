# Task 35 — Protses versiyalash (checksum bilan aniqlash + versiya qayerda kerak)

> **Ish papkasi:** `bpms/bpms-new-backend/`. Eski bpms READ-ONLY.
> **Muammo (foydalanuvchi):** "protsesni yuklaganimda `process_definition`da version bor, lekin **yangi versiyami
> yo'qmi bilmayman**."

---

## 0. Hozirgi holat (kod bo'yicha — grounding)
- `ProcessEngineService` deploy: `int version = definitions.nextVersion(processKey)`.
- `JpaPersistenceAdapter.nextVersion`: `select coalesce(max(version),0)+1 from process_definition where process_key=?`
  → **har deploy = yangi versiya** (max+1), kontent bir xil bo'lsa ham.
- Insert oldidan: `update process_definition set is_latest=false where tenant_id=? and process_key=? and is_latest=true`
  → keyin yangisi `is_latest=true`.
- `bpmn_checksum` (SHA256) ustuni **bor, lekin ishlatilmaydi**.
- ⚠️ **Bug:** `nextVersion` faqat `process_key` bo'yicha (tenant_id yo'q) — multi-tenant'da noto'g'ri (global max).

**Xulosa:** `process_definition` **allaqachon versiya jadvali** (har qator = bitta versiya). Yetishmayotgani:
(a) kontent o'zgarganini **aniqlash** (checksum), (b) deploy javobi "yangimi?" deb aytishi, (c) versiyani oson
ko'rish (katalog).

---

## 1. Checksum bilan versiya aniqlash (asosiy tuzatish)
Deploy'da:
1. BPMN'ni **kanonik** qil (whitespace/atribut tartibi normalize — aks holda bir xil model boshqa checksum beradi),
   `bpmn_checksum = SHA256(canonicalXml)`.
2. `(tenant_id, process_key)` bo'yicha **is_latest** definitionni top.
3. **Agar latest.checksum == yangi checksum** → **YANGI VERSIYA YARATILMAYDI**: mavjud versiyani qaytar,
   javobda `changed=false` (foydalanuvchi biladi: "bir xil, v3 qayta ishlatildi").
4. **Aks holda** → `version = max(version)+1` **(tenant bo'yicha!)**, `is_latest` flip, `changed=true`.

> **Variant (siyosat):** ba'zi jamoalar "har deploy = yangi versiya" ni afzal ko'radi (audit uchun). Shuning uchun
> `bpms.versioning.dedup-identical=true|false` config — default `true` (checksum dedup). `false` bo'lsa hozirgidek.

## 2. Deploy javobi (foydalanuvchi "biladi")
`POST /api/v1/deployments` javobi aniq bo'lsin:
```json
{ "definitionId": "...", "processKey": "TUNE_CREDIT_REQUEST",
  "version": 3, "isLatest": true, "changed": false, "checksum": "ab12…" }
```
`changed=false` → "yangi versiya emas, mavjudi qaytdi". `changed=true, version=4` → "yangi versiya yaratildi".

## 3. `nextVersion` tuzatish (tenant-scoped)
`select coalesce(max(version),0)+1 from process_definition where tenant_id=? and process_key=?` — tenant qo'shilsin.
`is_latest` flip ham `tenant_id` bilan (allaqachon shunday).

## 4. Versiya QAYERDA kerak — jadval auditi
> Tamoyil: **versiya anchor'i = `process_definition`** (yagona haqiqat). Instance bitta versiyani (definition_id)
> ishlatadi. Qolgan runtime jadvallar versiyani **instance orqali** oladi (denorm shart emas), **bittasidan tashqari**.

| Jadval | Versiya bilan aloqasi | Alohida versiya ustuni kerakmi? |
|---|---|---|
| `process_definition` | **Anchor** — `version` + `is_latest` + `bpmn_checksum` | ✅ bor (checksum ishlatilsin) |
| `process_deployment` | Deploy hodisasi (≠ versiya) | ❌ (deploy ichida bir nechta def bo'lishi mumkin) |
| `process_instance` | Bitta versiyani yuritadi (`definition_id`) | ⭐ **denorm tavsiya:** `definition_key` + `definition_version` — "bu instance qaysi versiyada" ni **join'siz** so'rash |
| `execution_token` / `_state` | instance orqali | ❌ (instance→definition) |
| `token_variable` (+history) | instance orqali | ❌ |
| `job` / `user_task` | instance orqali | ❌ |
| `execution_log` | instance orqali | ❌ |
| `connector_definition` | `definition_id`ga bog'liq | ✅ allaqachon versiya-scoped (FK) |
| `event_subscription` (START — timer/message start) | Qaysi **versiya** avtomat boshlanadi | ⭐ `definition_id`ga bog'lansin (odatда **is_latest**) — reja 28 start eventда |
| `process_instance_migration` | `source_definition_id` + `target_definition_id` (versiyalar) | ✅ bor |
| `process_stats_daily` | `definition_id` bo'yicha | ✅ versiya-scoped |

**Xulosa — qo'shiladigan yagona narsalar:**
1. `process_instance`ga **denorm** `definition_key` + `definition_version` (query qulaylik; migration/monitoring uchun).
2. `event_subscription` start obunasi `definition_id`ga bog'lansин (reja 28 bilan — qaysi versiya boshlanadi).

## 5. "Versiya jadvali" — katalog ko'rinishi (VIEW / endpoint)
Foydalanuvchi versiyalarni **ko'rishi** uchun — alohida jadval EMAS (u `process_definition`da), balki **read-only
view/endpoint**:
- `GET /api/v1/process-definitions?key=TUNE_CREDIT_REQUEST` → barcha versiyalar: `version`, `is_latest`, `checksum`,
  `deployed_at`, `deployed_by`, `status`, va **`running_instances`** (shu versiyada nechta RUNNING instance).
- (ixtiyoriy) DB view `v_process_versions`: `process_key, version, is_latest, checksum, deployed_at,
  running_instances, total_instances` — bir qarashда versiya tarixi.

## 6. Start-by-key → is_latest
`POST /process-instances` `ref`ni **process_key** bilan berса — engine **is_latest** versiyani tanlasin (aniq
versiya kerak bo'lsa `ref=key:version`). Shунда yangi deploy avtomат yangi instance'larга qo'llanади, eskilari
o'z versiyasида qolади (migration — reja bo'yicha `process_instance_migration`).

## 7. DoD
- [x] Deploy: kanonik BPMN → `bpmn_checksum` (SHA256); latest checksum bilan solиштириш; bir xил → yangi versiya
      YO'Q (mavjudi qaytadi), farqли → max+1 + is_latest flip. Config `dedup-identical` (default true).
- [x] Deploy javobi: `version`, `isLatest`, `changed`, `checksum`.
- [x] `nextVersion` **tenant-scoped**.
- [x] `process_instance`ga denorm `definition_key` + `definition_version` (migratsiya + monitoring qulaylik).
- [x] Katalog: `GET /process-definitions?key=…` versiyalar + `running_instances`; (ixtiyoriy) `v_process_versions` view.
- [x] Start-by-key → is_latest; `ref=key:version` → aniq versiya.
- [x] Test: bir xil BPMN 2× deploy → **1 versiya** (`changed=false`); o'zgartirib deploy → v2 (`changed=true`,
      is_latest v2ga o'tади, v1 is_latest=false).
- [x] Eski bpms 0 diff.

## 8. Cursor topshirig'i
```
Ish papkasi: bpms/bpms-new-backend/. docs/plans/35-process-versioning.md ni bajar.
1) Deploy'da bpmn_checksum (SHA256 kanonik XML) hisobla; is_latest definition checksum bilan solishtir; bir xil ->
   yangi versiya yaratma (mavjudini qaytar, changed=false); farqli -> max(version)+1 (TENANT bo'yicha) + is_latest flip,
   changed=true. Config bpms.versioning.dedup-identical (default true).
2) Deploy javobiga version/isLatest/changed/checksum qo'sh.
3) process_instance ga denorm definition_key + definition_version (migration/monitoring uchun).
4) GET /process-definitions?key=... -> versiyalar ro'yxati + running_instances; start-by-key -> is_latest.
Test: bir xil BPMN 2x -> 1 versiya; o'zgargan -> v2. Avval ProcessEngineService deploy + JpaPersistenceAdapter
nextVersion/insert ni o'qib rejani ayt, keyin yoz. Eski bpms 0 diff.
```
