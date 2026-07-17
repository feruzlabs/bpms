# Task — Definition cache (runtime re-parse'ni yo'qotish; XML canonical)

> **Ish papkasi:** `bpms/bpms-new-backend/` (yozish faqat shu yerda; eski bpms READ-ONLY).
> **Maqsad:** BPMN har so'rovda qayta parse qilinmasin. Parse **ta'rif uchun bir marta** (deploy + cache-miss).
> Yetuk enginelar (Camunda/Flowable) usuli: **BPMN XML — canonical**, parse natijasi **xotira cache**'da.
> Kod yozishdan oldin quyidagi fayllarni o'qib chiq.

## Muammo (tasdiqlangan)
Runtime'da BPMN har safar qayta parse qilinadi (100–200 KB XML):
- `bpms-server/.../service/ProcessEngineService.java` → `start` (~87), `completeTask` (~127):
  `parser.parse(d.bpmnXml().getBytes()).definition()`.
- `bpms-server/.../service/ServiceTaskJobHandler.java` (~74): xuddi shunday, **har async connector job'da**.
- `deploy` (~68) — bir marta parse (bu TO'G'RI, validatsiya uchun; qoladi).
- `process_definition.parsed_json` ustuniga deploy'da literal `"{}"` yoziladi (izoh: "avoids sealed-type Jackson
  graph") — ya'ni ustun **o'lik**, hech qachon model sifatida o'qilmaydi.

## Yechim: `DefinitionRegistry` (in-memory cache) + XML canonical

### 1. `DefinitionRegistry` (bpms-engine — SPI portlarga bog'lanadi)
```java
public interface DefinitionRegistry {
    ProcessDefinition get(String definitionId);   // cache'dan yoki DB-XML'dan parse (miss'da)
}
```
Impl:
- Cache: **Caffeine `Cache<String, ProcessDefinition>`** — `maximumSize` (masalan 500) + `expireAfterAccess` (masalan
  6h). Ta'rif **o'zgarmas + versiyalangan** → invalidatsiya SHART EMAS (yangi versiya = yangi id). Bounded LRU: issiq
  ta'riflar cache'da, sovuqlari evict bo'lib, keyingi kamdan-kam ishlatishda qayta parse. (Kichik loyihada
  `ConcurrentHashMap` ham yetadi, lekin ko'p/yillar davomida yig'iladigan versiyalar uchun bounded LRU afzal.)
- Miss'da: `DefinitionRepositoryPort.findDefinitionById(id)` → `bpmnXml` → `ProcessDefinitionParser.parse(...)` →
  `definition`. **Parse'ni lock ichida ushlab turma:** avval `get`, yo'q bo'lsa parse qilib `putIfAbsent`
  (uzoq parse paytida bin-lock ushlanmasin). Format-agnostik: parser SPI orqali (source_format bo'yicha).
- Thread-safe: model immutable → ulashish xavfsiz.

### 2. Chaqiruvchilarni cache'ga ulash
- `ProcessEngineService.start` va `completeTask`: `parser.parse(...)` **o'rniga** `registry.get(definitionRecord.id())`.
- `ServiceTaskJobHandler`: xuddi shunday — job payload'ida `definitionId` bo'lsin (yoki instance→definition orqali),
  `registry.get(definitionId)`.
- `deploy`: parse bir marta qoladi (validatsiya); natijani cache'ga **warm** qilish mumkin (ixtiyoriy —
  `registry` interfeysiga `put` qo'shmasdan ham, birinchi `start` cache'ga yuklaydi). Warm afzal: deploydan keyin
  darhol tayyor.

### 3. `parsed_json` ustunini olib tashlash (canonical = XML)
- V2 migration: `alter table process_definition drop column parsed_json;`
- `JpaPersistenceAdapter` insert/select + `DefinitionRecord` dan `parsed_json`/`parsedJson` maydonini olib tashla.
- Sabab: parse natijasi **hosila (derived)**; uni XML bilan yonma-yon saqlash sinxronlik/staleness muammosi
  (parser/model o'zgarsa jsonb eskiradi). Canonical = XML, derived = xotira cache.
- (Agar kelajakda BI/read-projeksiya kerak bo'lsa — u **alohida** loyihalanadi, ijro yo'lida emas.)

## Miqyos (scaling) — nega 1000lab instance muammo emas
- Parse **TA'RIF (type+version) bo'yicha** cache'lanadi, **instance bo'yicha EMAS**.
- 10 tur protsess × bir necha versiya ≈ **~10–50 ta ta'rif** → har biri **JVM umrida bir marta** parse (~ms), keyin
  cache'dan. Xotirada ~10–50 model (kichik).
- **1000lab instance** o'sha ~10–50 cache'langan modelni **ulashadi** → instance yaratish/ijrosi **qo'shimcha parse
  qilmaydi** (nol). 1000 ta instance = 0 ta qo'shimcha parse.
- Ya'ni parse narxi = O(ta'rif soni), **O(instance/so'rov soni) EMAS**. Aynan hozirgi bug (har so'rovda parse) buni
  buzadi — cache uni tuzatadi.
- Yillar davomida ko'p versiya yig'ilsa: bounded LRU (yuqorida) issiqni saqlaydi, sovuqni evict qiladi (kamdan-kam
  qayta parse). Xotira portlashi yo'q.

## Nega bu to'g'ri (qaror asosi)
- Parse har ta'rif uchun **JVM umrida bir marta** (so'rov boshiga emas) → 100–200 KB XML qayta-qayta parse'i yo'qoladi.
- Ta'rif immutable+versiyalangan → cache **invalidatsiyasiz** (eng sodda to'g'ri holat).
- Polimorf jsonb-seriyalash murakkabligi va staleness **kiritilmaydi** — Camunda/Flowable ham shunday (XML canonical + deployment cache).
- Multi-node kelajakda: har node bir marta parse (arzon).

## Testlar (majburiy)
- **Re-parse yo'qligi:** `ProcessDefinitionParser` ni spy/mock qilib — 1 marta deploy + N marta `start`/`complete`/job →
  o'sha definition uchun `parse(...)` **1 marta** (yoki cache-warm bilan deploydagi 1 marta) chaqirilishini tasdiqla.
- **Cache-miss (restart simulatsiyasi):** yangi `DefinitionRegistry` instansiyasi → birinchi `get` parse qiladi,
  keyingilari cache'dan.
- **Concurrency:** bir definition'ni parallel `get` → natija to'g'ri, thread-safe (parse ko'pi bilan bir-ikki marta,
  crash/deadlock yo'q).
- **Regressiya:** ijro natijalari o'zgarmagan (mavjud engine/corpus testlari yashil qoladi).
- **Migration:** V2 drop qo'llanadi; deploy/start smoke (§ plan 15 §9) ishlaydi.

## DoD
- [ ] `DefinitionRegistry` (ConcurrentHashMap cache, parse-on-miss, thread-safe, parse lock-siz).
- [ ] `start`/`completeTask`/`ServiceTaskJobHandler` cache orqali — runtime'da `parser.parse` YO'Q (faqat deploy + miss).
- [ ] `parsed_json` ustuni drop (V2) + adapter/record tozalangan; `"{}"` yozish yo'q.
- [ ] Testlar: 1 marta parse (spy), cache-miss, concurrency, regressiya — yashil.
- [ ] Eski bpms 0 fayl diff. Stack Java 21 / Boot 3.5.14 o'zgarmagan.

## Cursor'ga topshiriq (namuna)
```
Yangi task. Ish papkasi: @bpms-new-backend.
@17-definition-cache-no-reparse.md ni bajar — runtime re-parse'ni yo'qot (DefinitionRegistry cache, XML canonical).
start/completeTask/ServiceTaskJobHandler'ni cache'ga ula; parsed_json ustunini V2 migration bilan drop.
Parser SPI orqali (format-agnostik). Eski bpms READ-ONLY. Avval fayllarni o'qib, planni ayt, keyin yoz.
```
