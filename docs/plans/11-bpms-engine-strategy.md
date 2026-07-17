# Plan 11 — BPMS dvigatel strategiyasi (texnik qaror hujjati)

> Maqsad: BPMS dvigatelini optimallashtirish/yangilash bo'yicha variantlarni **texnik mezonlar** asosida taqqoslash va
> tavsiya berish. Bu hujjat qaror qabul qilishga asos va kelajak uchun yozma tahlil.

## 1. Kontekst
- **Muammo:** protsesslarni bajarish resurs-talab (unumdorlik / masshtablanish).
- **Joriy yadro:** `InstanceTokenStateService` god-class (~1600 LOC), avtomatik test yo'q, connector dispatch
  type-safe emas (`getBean(String)` — xato `connectorId` runtime'da bilinadi), v2..v8 copy-paste.
- **Holat:** v9 tayyor, hali ishga tushmagan; v1–v8 jonli.
- **Talab:** yangi versiyalarni (v9/v10) toza, optimallashtirilgan asosda ishlatish; **eski versiyalar buzilmasin**.

## 2. Variantlar

### A) Hotspot optimizatsiya (mavjud yadroni saqlab qolib)
- **Profiling** → aniq sekin nuqtalarni tuzatish: indekssiz so'rovlar, N+1, jsonb/TOAST, RabbitMQ consumer sozlamalari,
  ortiqcha serializatsiya/DB murojaatlari.
- Ish hajmi: **kichik**. Risk: **past-o'rta**. Unumdorlik: muammo lokal bo'lsa **katta yutuq**.

### B) Yonma-yon yangi custom engine (deployment darajasida Strangler)
- Yangi **toza, optimallashtirilgan** engine — alohida deployment — **yangi versiyalar** (v9/v10) uchun. Eski engine —
  **eski versiyalar** uchun o'z joyida. Gateway/input-service protsess-versiyaga qarab qaysi engine'ga borishni tanlaydi.
- Connector'lar (v9) qayta ishlatiladi.
- Ish hajmi: **katta** (yadro ≈ butun ilova, connector'lardan tashqari). Risk: **o'rta** (eski tegilmaydi, lekin yangi
  engine to'liq va to'g'ri ishlashi shart). Nazorat: **to'liq**.

### C) Yetuk engine kutubxonasini embedded ishlatish
- BPMN allaqachon **Camunda XML formatida** → embedded engine (Flowable / Camunda 7) ni Spring Boot ichida ishlatish;
  connector'lar "job worker"/delegate sifatida ulanadi.
- Ish hajmi: **o'rta** (integratsiya + connector moslash + BPMN moslik). Risk: **o'rta**. Unumdorlik/masshtab: **yuqori**
  (jangovar-sinovdan o'tgan). Nazorat: **cheklangan** (tashqi kutubxonaga bog'liqlik).

### D) Taqsimlangan engine (Zeebe) — (yozib qo'yiladi, katta yuk uchun)
- Gorizontal masshtablanuvchi; juda katta throughput uchun. Ish hajmi: **katta** (infratuzilma + operatsion murakkablik).

## 3. Taqqoslash (texnik mezonlar)
| Mezon | A: optimizatsiya | B: custom yonma-yon | C: embedded engine |
|---|---|---|---|
| Ish hajmi | Kichik | Katta | O'rta |
| Prod risk (eski oqim) | Past–o'rta | O'rta | O'rta |
| Unumdorlik yutug'i | Lokal-bog'liq | Yaxshi | Yuqori |
| Masshtablanish | Cheklangan | Dizaynga bog'liq | Yuqori |
| Kod-saqlash/kengaytirish | O'rta | Yaxshi | Yaxshi |
| To'liq nazorat | To'liq | To'liq | Cheklangan |
| Tashqi bog'liqlik | Yo'q | Yo'q | Bor (kutubxona) |

## 4. Tavsiya (texnik, bosqichma-bosqich)
1. **Avval A — profiling + hotspot optimizatsiya.** Arzon, tez, o'lchanadigan. Ko'p hollarda muammo 2–3 joyda bo'ladi
   (PHP tomonda ko'rganimizdek: indeks, jsonb/TOAST). Balki katta o'zgarishsiz yetarli bo'ladi.
2. **A yetmasa → B (yonma-yon custom engine)** — yangi versiyalar uchun toza asos, eski buzilmaydi.
3. **Har holatda: optionlikni saqlang** — yangi engine'ni **port/adapter** abstraksiyasi bilan quring (quyida). Shunda
   ostidagi ijro mexanizmi (custom yoki C-embedded) keyin **kodni buzmasdan** almashtirilishi mumkin.

## 5. Qaror mezoni (raqamga asoslangan — "ko'r-ko'rona" emas)
Qaror faqat o'lchovlardan keyin:
- **Profiling natijasi:** resurs/latency qayerda ketyapti (DB, token-walk, RabbitMQ, serializatsiya)?
- **Talab raqamlari:** kerakli throughput (protsess/sek), latency (ms), bir vaqtdagi instance soni.
- Shu raqamlar A'ni oqlasa — A. Agar arxitekturaviy chegara bo'lsa (masshtab) — B yoki C.

## 6. "Optionlikni saqlash" arxitekturasi (B yoki C tanlansa)
Domen (connector'lar) ijro-mexanizmidan **mustaqil** bo'lsin:
- **`ExecutionPort`** interfeysi — `start(process, businessKey, vars)`, `signal(token)`, `complete(...)`.
- **`ConnectorPort`** interfeysi — serviceTask ijrosi (job worker shakli).
- Custom engine ham, embedded engine ham shu port'larni **implementatsiya** qiladi → biri ikkinchisiga almashtirilsa
  connector/domen kodi **o'zgarmaydi** (Hexagonal / Ports-and-Adapters).

## 7. Old shartlar (B ga kirishishdan oldin)
- v9 to'liq ishga tushgan va barqaror.
- Profiling qilingan, talab raqamlari aniq.
- Port/adapter abstraksiyasi kelishilgan.
- Characterization test (eski xatti-harakatni "muzlatib" olish) — hech bo'lmasa kritik oqimlar uchun.

## 8. Xulosa
Texnik jihatdan eng oqilona ketma-ketlik: **A (o'lchash + optimizatsiya) → kerak bo'lsa B (yonma-yon custom engine,
port/adapter bilan)**. Embedded engine (C) — masshtab/unumdorlik asosiy talab bo'lsa kuchli variant. Katta engine
qarori **raqamlardan keyin** qabul qilinadi.
