# Plan 19 — Ijro logi (DB): connector/gateway/instance loglarini bazaga yozish

> **Maqsad:** protses ijrosini (ayniqsa connector chaqiruvlari va xatolari) **bazaga** yozib, "nega bunday chiqdi"ni
> keyin tekshirish. Misol: `GetScoringResultV9Connector` → `Network is unreachable` → `isOk=false` → gateway "rejected".
> Bu log DB'da qolsa, har instance uchun sabab ko'rinadi.
> **Ish papkasi:** `bpms/bpms-new-backend/`. Eski bpms READ-ONLY.

---

## 0. Hozirgi holat (grounding)
- Engine/server'da **hech qanday logging yo'q** (na SLF4J, na DB). Xato faqat javobda (`errMsg` o'zgaruvchisi) ko'rinadi.
- **`ExecutionEngine.executeConnector(token, businessKey, connectorId, inputs)`** — barcha connector chaqiruvlari
  (sync + async consumer `ServiceTaskJobHandler`) shu yerdan o'tadi → **log integratsiya nuqtasi shu**.
- Ko'chirilgan connectorlar xatoni yutadi (`ConnectorResult.ok(...)` + `isOk=false`/`errMsg`) — demak engine-log
  `outputs`ni yozsa, xato xabari (`errMsg`) avtomat qamrab olinadi.

## 1. Jadval (Flyway V-next)
```sql
create table execution_log (
  id           bigserial primary key,
  instance_id  varchar(64) not null,
  token_id     varchar(64),
  node_id      varchar(255),
  node_type    varchar(64),
  connector_id varchar(255),
  event_type   varchar(32) not null,   -- CONNECTOR_START|CONNECTOR_END|CONNECTOR_ERROR|GATEWAY|INSTANCE_START|INSTANCE_END|TOKEN_FAILED
  status       varchar(32),            -- OK|FAIL
  message      text,                   -- xato xabari yoki qaror izohi
  details      jsonb,                  -- {inputs, outputs, chosenFlow, ...}
  duration_ms  integer,
  created_at   timestamptz not null
);
create index ix_execution_log_instance on execution_log(instance_id, created_at);
```

## 2. SPI port + JPA adapter
```java
// bpms-spi
public interface ExecutionLogPort {
    void log(LogEntry e);
    List<LogEntry> byInstance(String instanceId);
    record LogEntry(String instanceId, String tokenId, String nodeId, String nodeType,
                    String connectorId, String eventType, String status, String message,
                    Map<String,Object> details, Integer durationMs, Instant createdAt) {}
}
```
`JpaPersistenceAdapter` (yoki alohida `JpaExecutionLogAdapter`) implement qiladi: `execution_log` ga insert
(details → jsonb, Jackson bilan), `byInstance` → `created_at` bo'yicha o'qish.

## 3. Engine integratsiyasi (log qo'yiladigan nuqtalar)

### 3.1 Connector (asosiy — `executeConnector`)
```java
public void executeConnector(TokenRecord token, String bk, String connectorId, Map<String,Object> inputs) {
    Instant t0 = clock.now();
    logPort.log(start(token, connectorId, inputs));                 // CONNECTOR_START
    Map<String,Object> vars = variables.getAll(token.instanceId());
    ConnectorResult result;
    try {
        result = connectors.required(connectorId).execute(new ConnectorContext(bk, vars, inputs));
    } catch (Exception e) {
        logPort.log(error(token, connectorId, e.getMessage(), dur(t0), inputs, null));  // CONNECTOR_ERROR
        // ... token FAILED (mavjud mantiq)
        throw ...;
    }
    if (!result.success()) {
        logPort.log(error(token, connectorId, result.errorMessage(), dur(t0), inputs, result.outputs()));
        // ... token FAILED (mavjud mantiq)
        return;
    }
    variables.putAll(token.instanceId(), result.outputs());
    logPort.log(end(token, connectorId, dur(t0), inputs, result.outputs()));   // CONNECTOR_END, status=OK
}
```
> Ko'chirilgan connectorlar `ok()` qaytargani uchun `CONNECTOR_END` yoziladi, lekin `details.outputs` ichida
> `isOk=false, errMsg="Network is unreachable"` bo'ladi → **sabab shu yerda ko'rinadi**.

### 3.2 Gateway qarori (run loop, exclusiveGateway tanlash joyi)
Qaysi flow tanlandi (`matched` yoki `default`) → `GATEWAY` log: `message="→ toApprove"` yoki `"→ toRejected (default)"`,
`details={condition, chosenFlowId}`. Bu "nega rejected"ga to'g'ridan-to'g'ri javob beradi.

### 3.3 Instance/token holati
- `start(...)` → `INSTANCE_START`; instance COMPLETED/FAILED → `INSTANCE_END` (status bilan); token FAILED → `TOKEN_FAILED`.

> **Tranzaksiya:** log insert ijro bilan bir tranzaksiyada (sync) — sodda va ishonchli. Keyin kerak bo'lsa async/batch
> (alohida yozuvchi) qilinadi; hozir sync yetadi.

## 4. O'qish (REST)
`GET /api/v1/process-instances/{id}/logs` → `List<LogEntry>` (`created_at` bo'yicha). Frontend/diagnostika uchun.
(Ixtiyoriy: `?eventType=CONNECTOR_ERROR` filtri.)

## 5. Ixtiyoriy — chuqurroq connector/HTTP detali
Ko'chirilgan connector xatoni yutadi (`errMsg` faqat xabar). To'liq sabab (HTTP URL, status kod, stack) kerak bo'lsa:
- `ConveyorClientV9` (yoki connector `catch`) `ExecutionLogPort` orqali qo'shimcha `CONNECTOR_ERROR` yozadi
  (`details={url, httpStatus, exceptionClass}`). Bu **ixtiyoriy** — baseline (§3.1) allaqachon `errMsg`ni beradi.
- **SLF4J** ham qo'shilsin (konsol/ops uchun) — arzon, DB-logdan alohida; lekin asosiy talab — DB.

## 6. Config
```yaml
bpms:
  execution-log:
    enabled: ${BPMS_EXEC_LOG:true}   # o'chirib qo'yish mumkin
```
`enabled=false` bo'lsa `logPort` no-op (ijroga ta'sir qilmaydi).

## 7. Definition of Done
- [ ] `execution_log` jadvali (Flyway); indeks `instance_id, created_at`.
- [ ] `ExecutionLogPort` + JPA adapter (details → jsonb).
- [ ] `executeConnector` da CONNECTOR_START/END/ERROR (inputs+outputs+duration) — `errMsg` DB'da ko'rinadi.
- [ ] Gateway qarori (matched/default) va instance/token holati loglanadi.
- [ ] `GET /process-instances/{id}/logs` ishlaydi.
- [ ] `credit-v9-smoke` ni qayta ishga tushirganda: DB'da CONNECTOR_END (outputs.errMsg="Network is unreachable") +
      GATEWAY (→ rejected) yozuvlari bor.
- [ ] `execution-log.enabled=false` da log yozilmaydi, ijro ishlaydi.
- [ ] Eski bpms 0 fayl diff.

## 8. Cursor'ga topshiriq (namuna)
```
Yangi task. Ish papkasi: @bpms-new-backend.
@19-db-execution-log.md ni bajar — ijro logini bazaga yoz.
execution_log jadval + ExecutionLogPort + JPA adapter + executeConnector'da CONNECTOR_START/END/ERROR
(inputs+outputs+duration) + gateway/instance loglari + GET /process-instances/{id}/logs.
Log insert ijro bilan bir tranzaksiyada (sync). Eski bpms READ-ONLY. Avval fayllarni o'qib, planni ayt, keyin yoz.
```
