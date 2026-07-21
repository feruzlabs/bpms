# Task 31 — Human-workflow: userTask / manualTask / terminateEnd / initiator (structural)

> **Ish papkasi:** `bpms/bpms-new-backend/`. Eski bpms READ-ONLY (parity manbasi).
> **Nega:** `processes/regional_vacation.bpmn` kabi **HR approval** protseslar sinfi — kredit sxemalaridan farqli
> (inson-markazли). v3 sxemada `user_task` jadvali bor, lekin engine userTask/manualTask/terminate'ni **yura
> olmaydi**. Bu reja **strukturaviy** qismini yopadi (skript-listener + `bpms.*` API — alohida, ADR-001 ga qarang).

---

## 0. Grounding (regional_vacation.bpmn)
7 `userTask` + 13 `manualTask` + 8 `exclusiveGateway` + 6 `terminateEndEvent` + 1 `startEvent` (`camunda:initiator`).
userTask: `camunda:formKey`, `camunda:assignee="EMPLOYEE__$..__empId"` (ifoda), `camunda:dueDate="$TASK_EXPIRED_DATE"`,
`camunda:priority`. Oqim: ketma-ket approval, har bosqichda sign yo'q → reject → terminate.

## 1. userTask ijrosi (asosiy)
Token userTask'ga kelganda:
```java
if (node instanceof UserTaskNode ut) {
    UserTaskRecord task = new UserTaskRecord(id, tenantId, token.id(), ut.id(),
        resolve(ut.assignee(), evalVars),           // "EMPLOYEE__$emp__empId" -> ifoda hisoblanadi
        ut.candidateGroups(), ut.candidateUsers(),
        resolveDate(ut.dueDate(), evalVars),         // "$TASK_EXPIRED_DATE" -> Instant
        ut.priority(), ut.formKey(), /*submitted*/ null, /*completed*/ false, clock.now());
    userTasks.save(task);
    token = token.waiting("WAITING");                // execution_token.status=WAITING
    tokenState.complete(stateId, COMPLETED);         // "kirish" step yopiladi
    return;                                          // tashqi complete kutadi
}
```
- **Assignee/dueDate ifoda:** `expression`modul orqali hisoblanadi (`$emp` → variable). Konvensiya
  `EMPLOYEE__$x__empId` eski koddan parity bilan o'qilsin (assignee formati).
- **Task forma:** userTask'dagi `camunda:formData/formField` (start-forma bilan bir xil parser — reja 30) →
  `user_task.form_key` + forma modeli; complete'da validatsiya.

## 2. userTask complete (davom)
`POST /api/v1/tasks/{id}/complete` `{ variables:{...} }`:
1. `user_task` topiladi (WAITING, completed=false), aks holda 404/409.
2. Task-forma bo'lsa `variables` validatsiya (reja 30 validator).
3. `variables` → `token_variable` (EAV); `user_task.submitted_data`, `completed=true`, `completed_at`.
4. `engine.resumeFrom(token, ut.id())` — token oldinga (keyingi gateway sign'ни tekshiradi).
- **Claim/assign (ixtiyoriy):** `POST /tasks/{id}/claim` — `assignee`+`claim_time`. Korpusда assignee ifodaдан
  keladi, shuning uchun claim opsional.

## 3. manualTask
BPMN'да manualTask — tizim ijrosisiz "qadam" (odatда tashqarида bajарилади). Bu protsesda ular
**reject/complete/sign** nuqtalари sifatida ishlatilган va **skript-listener** olib boradi (ADR-001). Strukturaviy
jиhatдан: manualTask **pass-through** (token darhol o'tади) — listener side-effect'lар ADR-001 bo'yicha
service-task'ga chиqарилса, manualTask sof pass-through bo'ladi.
```java
if (node instanceof ManualTaskNode) { /* listener (agar qolgan bo'lsa) -> keyin */ advance(token); }
```
> Agar skript-listener hoz ircha 1:1 ko'chиrилса (ADR-001 "replicate" varianti) — manualTask start/end listener'lар
> reja 23 lifecycle'ида ishlaydi. Best-practice (ADR-001 "externalize") — manualTask'lар serviceTask bo'lиб ketади.

## 4. terminateEndEvent
`endEvent` ичида `terminateEventDefinition` bo'lsa — oddiy end emas: **butun instance'ни darhol tugатади**
(barcha faol token CANCELED), status `TERMINATED` yoki `COMPLETED`? — BPMN semantikasи: terminate end **instance'ни
tugатади** (barcha token to'xтайди). Status **COMPLETED** (normal tugаш, reject emas — reject business-holat,
technical emas).
```java
if (node instanceof EndEventNode e && e.isTerminate()) {
    tokens.cancelAllActive(instanceId);              // qolgan parallel token'lар
    instances.complete(instanceId, COMPLETED, clock.now());
    return;
}
```
> **Muhim farq (reja 27 bilan):** terminateEnd — **model ичидаги normal** tugаш (approval reject → oqим terminate
> end'ga boradi). Reja 27 TERMINATE — **tashqи admin** aralashuvi (runaway). Ikkisi har xil: biri BPMN element,
> ikkinchisi REST API + guard.

## 5. camunda:initiator
`startEvent camunda:initiator="starterVar"` — instance boshланганда boshlovchи foydаланувчи `starterVar`
o'zгарувчисига (va `process_instance.created_by`ga) yozилади. Start API `startedBy` (header/JWT/param)дан oladi.

## 6. input/output mapping (camunda:inputOutput)
Task/element'да `camunda:inputParameter`/`outputParameter` — element ijrosидан oldин/keyin variable map. userTask'да
`fail_expression`, `task_level`, `special_order` kabi — o'qиб `token_variable`ga yoki task metadata sifatiga
qo'yилsин (parity eski koддан).

## 7. Parser (parser-camunda)
`userTask` (formKey, assignee, dueDate, priority, formData, inputOutput), `manualTask`, `endEvent`+
`terminateEventDefinition`, `startEvent camunda:initiator`ни node modelга chиqариш. Reja 23 listener parsing bilan
mos (BEFORE/AFTER).

## 8. Test (majburiy)
- **userTask wait/complete:** token userTask'да WAITING + `user_task` qатор (assignee ifodаdан hisoblanган);
  `POST /tasks/{id}/complete` → token oldinga.
- **Reject → terminateEnd:** sign null → gateway reject tarmog'и → terminate end → instance COMPLETED, token to'xтади.
- **Approval chain (regional_vacation skeleti):** Employee→(sign)→...→Center head→Complete → COMPLETED; har
  bosqichда sign null → reject → terminate.
- **initiator:** start `startedBy=U1` → `created_by=U1` + initiator var.
- **manualTask pass-through:** listener'sиз manualTask → token darhol o'tади.

## 9. DoD
- [ ] userTask: WAITING + `user_task` (assignee/dueDate/priority ifoda, form); `POST /tasks/{id}/complete` → davom.
- [ ] manualTask pass-through (listener ADR-001 bo'yicha).
- [ ] terminateEndEvent — instance'ни tugатади (barcha token); reja 27 TERMINATE'дан ajratilган.
- [ ] camunda:initiator → `created_by`.
- [ ] input/output mapping.
- [ ] Parser bularни o'qийди; regional_vacation skeleti testда yuрadi (skript-listener'сиз, ADR-001 externalize bilan).
- [ ] Eski bpms 0 diff.

## 10. Bog'liqlik
- **Task-forma validatsiyasi** — reja 30 validator (qayta ishlatilади).
- **Listener trace** — reja 23 (BEFORE/AFTER yozuvi).
- **Skript-listener + `bpms.*`** — **ADR-001** (bu rejaда YO'Q; strukturани shu 31 beradi, skript logikasи alohida qaror).

## 11. Cursor topshirig'i — quyida (alohida).
