# Fix task — EAV variable typing (kasrli/decimal qiymat bug'i)

> **Ish papkasi:** `bpms/bpms-new-backend/` (yozish faqat shu yerda; eski bpms READ-ONLY).
> **Turi:** bug-fix + typing kengaytirish. Kichik, aniq scope. Kod yozishdan oldin quyidagi fayllarni o'qib chiq.

## Muammo (tasdiqlangan)
`bpms-persistence-jpa/.../JpaPersistenceAdapter.java`:
- `type(Object)` (~219-qator) **har qanday `Number` ni `"long"`** deb belgilaydi.
- `getAll(...)` o'qish switch'i `case "long" -> Long.valueOf(value)` qiladi.

⇒ **Kasrli qiymat** (masalan `amount=150.50`, `rate=12.5`) `Long.valueOf("150.50")` → **`NumberFormatException`** →
`getAll` uziladi → ijro/gateway shartlari sinadi. Credit-scoring'da qiymatlarning ko'pi kasrli, shuning uchun bu
jiddiy. Qo'shimcha: `type="date"` yoziladi, lekin o'qishda `date` case **yo'q** → sana String bo'lib qaytadi
(nomuvofiqlik).

Bu `POST /api/v1/process-instances` (`variables`), `POST /{id}/complete`, connector output — **hamma o'zgaruvchi
yozish yo'liga** ta'sir qiladi (hammasi `VariableStorePort.putAll` orqali `token_variable` ga boradi).

## Kutilgan xatti-harakat (canonical EAV tip-xaritasi)

`token_variable.type` (varchar) qiymatlari va round-trip:

| Java tip (kiruvchi) | `type` | Yozish (`value_text`/`value_json`) | O'qish (qaytadigan tip) |
|---|---|---|---|
| `Integer`, `Long`, `Short`, `BigInteger` | `long` | value_text = raqam | `Long` |
| `Double`, `Float`, **`BigDecimal`** | `double` | value_text = raqam | **`BigDecimal`** (pul uchun aniqlik) |
| `Boolean` | `boolean` | value_text = `true/false` | `Boolean` |
| `TemporalAccessor` (LocalDate/LocalDateTime/…) | `date` | value_text = **ISO-8601** satr | `String` (ISO) — SpEL/consumer o'zi ishlaydi* |
| `String` | `string` | value_text | `String` |
| `Map`/`List`/boshqa obyekt | `json` | value_json (jsonb) | `Map`/`List` (Jackson) |
| `null` | `string` (yoki alohida `null`) | value_text = NULL | `null` (skip yoki NULL) |

\* Sana uchun hozircha ISO-string round-trip yetarli; keyin kerak bo'lsa eski engine `datetime/time` tiplariga
kengaytiriladi. **Muhimi — hech qachon `NumberFormatException` bermasin.**

## Kritik: decimal/pul aniqligi
- `POST` body `variables: Map<String,Object>` — Jackson JSON `150.50` ni default **`Double`** qiladi (float xatosi
  bo'lishi mumkin). Pul uchun **`BigDecimal`** afzal. Yechim: o'zgaruvchilarni deserializatsiya qiladigan
  `ObjectMapper` da `DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS = true` yoqing (butun app mapper yoki
  variable-parsing uchun alohida mapper). Shunda kasr qiymatlar `BigDecimal` bo'lib keladi → `type="double"` →
  `value_text` → o'qishda `new BigDecimal(text)`.
- SpEL gateway shartlari (`${amount > 100}`) `BigDecimal`/`Long` bilan to'g'ri ishlaydi.

## O'zgarish (aniq)
1. `JpaPersistenceAdapter.type(Object)` — yuqoridagi jadval bo'yicha `Number` ni `long`/`double` ga ajrating
   (`Double|Float|BigDecimal` → `double`, aks holda butun → `long`).
2. `getAll(...)` read switch — `case "double" -> new BigDecimal(value)` qo'shing; `long`/`boolean`/`json` qoladi;
   `date`/default → String.
3. `ObjectMapper` — `USE_BIG_DECIMAL_FOR_FLOATS` yoqing (variable parsing uchun).
4. **Migration SHART EMAS** — `token_variable.type` allaqachon `varchar`, yangi qiymatlar (`double`) sig'adi.
   (Ixtiyoriy kelajak: `value_num numeric` ustuni SQL-darajali solishtirish uchun — hozir kerak emas.)

## Testlar (majburiy)
`bpms-persistence-jpa` (yoki integration) da `VariableStorePort` round-trip testi:
- `long` (150), **`double`/decimal (150.50, 12.5)**, `boolean`, `date` (LocalDate/LocalDateTime), `string`, `json`
  (Map/List), `null` — yozib-o'qib, tip va qiymat saqlanishini tekshiring.
- **Regres:** decimal qiymat endi `NumberFormatException` bermaydi.
- Engine testi: gateway `${amount > 100}` `amount=150.50` bilan `true` beradi (String emas, son sifatida).

## DoD
- [ ] Kasrli qiymat (`double`/`BigDecimal`) yozib-o'qiladi, exception yo'q.
- [ ] `type()` va read switch canonical xarita bo'yicha; `double` case bor.
- [ ] `USE_BIG_DECIMAL_FOR_FLOATS` yoqilgan (pul aniqligi).
- [ ] Round-trip testlar (long/double/boolean/date/string/json/null) yashil.
- [ ] Gateway raqamli shart decimal bilan ishlaydi.
- [ ] Eski bpms 0 fayl diff; migration qo'shilmagan (yoki qo'shilsa, additive).

## Cursor'ga topshiriq (namuna)
```
Yangi task. Ish papkasi: @bpms-new-backend.
@16-fix-eav-variable-typing.md ni bajar — EAV variable typing bug (decimal→long crash).
Faqat JpaPersistenceAdapter (type + getAll) + ObjectMapper config + testlar. 
Migration qo'shma (type varchar). Eski bpms READ-ONLY. Avval fayllarni o'qib, planni ayt, keyin yoz.
```
