# Task 24 — Migratsiyani Flyway'dan Liquibase'ga o'tkazish

> **Ish papkasi:** `bpms/bpms-new-backend/`. Eski bpms READ-ONLY (u o'z Flyway'ida qoladi, tegilmaydi).

## 0. Nima o'zgaradi
- Dependency: `flyway-core` (+ `flyway-database-postgresql`) o'chiriladi, `liquibase-core` qo'shiladi.
- Fayl joylashuvi: `src/main/resources/db/migration/V*.sql` (Flyway) → `src/main/resources/db/changelog/`
  (Liquibase). Format — **SQL-formatted changelog** (`--liquibase formatted sql`), chunki jamoa SQL'ga o'rgangan,
  XML/YAML shart emas.
- Tracking jadval: Flyway'ning `flyway_schema_history` o'rniga Liquibase o'zining `databasechangelog` +
  `databasechangeloglock` jadvallarini avtomatik yaratadi (qo'lda yozilmaydi).

## 1. Maven/Gradle
```xml
<!-- olib tashlanadi -->
<dependency><groupId>org.flywaydb</groupId><artifactId>flyway-core</artifactId></dependency>
<dependency><groupId>org.flywaydb</groupId><artifactId>flyway-database-postgresql</artifactId></dependency>

<!-- qo'shiladi -->
<dependency><groupId>org.liquibase</groupId><artifactId>liquibase-core</artifactId></dependency>
```

## 2. `application.yml` (yoki `.properties`)
```yaml
spring:
  liquibase:
    enabled: true
    change-log: classpath:db/changelog/db.changelog-master.sql
  # flyway bloki butunlay olib tashlanadi
```

## 3. Papka tuzilishi
```
src/main/resources/db/changelog/
  db.changelog-master.sql          # includeAll — barcha changeset fayllarini tartib bilan chaqiradi
  001-process-definition.sql
  002-process-instance.sql
  003-execution-token.sql
  004-execution-token-state.sql    # (plan 23 — rename + yangi ustunlar shu yerda)
  005-execution-listener-log.sql   # (plan 23 — yangi jadval)
  006-token-variable.sql
  007-job.sql
  008-user-task.sql
  009-execution-log.sql
```
Master fayl (`db.changelog-master.sql`):
```sql
--liquibase formatted sql

--changeset bpms:master-include runOnChange:false
-- (Liquibase SQL formatida includeAll yo'q — shuning uchun har fayl alohida <include> XML orqali YOKI
--  quyidagicha bitta root XML/YAML bilan chaqiriladi. Ikkita variant bor, birini tanlang:)
```
> **Muhim eslatma:** Liquibase SQL-formatted fayllarda `includeAll` ishlamaydi (bu faqat XML/YAML/JSON root
> changelog'da bor). Shuning uchun root fayl **XML yoki YAML** bo'lishi kerak, ichidagi har bir changeset esa
> SQL fayl bo'lib qoladi:
```yaml
# db.changelog-master.yaml
databaseChangeLog:
  - includeAll:
      path: db/changelog/
      relativeToChangelogFile: true
```
Bu tavsiya etilgan yondashuv — root YAML (bitta marta yoziladi, keyin tegilmaydi), ichidagi har bir migratsiya
esa siz to'ldiradigan **SQL fayl** (`--liquibase formatted sql` header bilan).

## 4. Eski Flyway `V*.sql` fayllarini ko'chirish
Har bir mavjud `V{n}__*.sql` faylini mos nomdagi SQL-formatted changelog'ga aylantiring:
```sql
--liquibase formatted sql

--changeset bpms:001-process-definition
CREATE TABLE process_definition (
    ...
);
```
`--changeset <author>:<id>` — `id` global unique bo'lishi kerak (fayl ichida ham, fayllar orasida ham).

## 5. Test/dev baza
- Lokal `bpms_new` (port 5433) bazasini **tozalab qayta ko'tarish** eng oson yo'l (Flyway va Liquibase bir xil
  bazani ikkalasi boshqarolmaydi — tracking jadvallar boshqa). Prod/staging bo'lsa — `liquibase generate-changelog`
  bilan hozirgi holatni "baseline" qilib import qilish kerak bo'ladi (hozircha dev bosqichida shart emas).

## 6. DoD
- [ ] `flyway-*` dependency olib tashlangan, `liquibase-core` qo'shilgan.
- [ ] `db.changelog-master.yaml` (root, includeAll) + har migratsiya alohida SQL-formatted fayl.
- [ ] Barcha mavjud Flyway `V*.sql'lar Liquibase changeset'ga ko'chirilgan (1:1, tartib saqlangan).
- [ ] `mvn spring-boot:run` (yoki gradle) — Liquibase avtomatik `databasechangelog`/`databasechangeloglock`
      yaratadi, barcha jadvallar to'g'ri tuziladi.
- [ ] Eski bpms 0 diff (uning Flyway'iga tegilmagan).

## 7. Claude Code (terminal) topshiriq
```
Ish papkasi: bpms/bpms-new-backend/. Eski bpms faqat o'qish uchun.

/tmp/24-flyway-to-liquibase-migration.md ni bajar: Flyway -> Liquibase (SQL-formatted changelog, root YAML
includeAll). Mavjud barcha V*.sql fayllarni 1:1 ko'chir. application.yml'ni yangila. Lokal bazani qayta ko'tarib
tekshir (mvn spring-boot:run yoki tegishli buyruq), databasechangelog jadvali to'g'ri yaratilganini tasdiqla.

Avval hozirgi migratsiya fayllar ro'yxatini o'qib chiq, rejani menga qisqa ayt, keyin yoz.
```
