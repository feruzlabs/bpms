package com.bpms.persistence.jpa;

import com.bpms.spi.engine.RuntimeModels.*;
import com.bpms.spi.port.*;
import com.bpms.spi.port.ListenerLogPort.ListenerLogEntry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

/** PostgreSQL persistence behind SPI ports (Liquibase-owned schema). */
@Repository
@Transactional
public class JpaPersistenceAdapter implements DefinitionRepositoryPort, InstanceRepositoryPort,
        TokenRepositoryPort, VariableStorePort, TaskRepositoryPort, JobRepositoryPort,
        TokenStatePort, ListenerLogPort, InstanceControlPort, IncidentPort, EventSubscriptionPort {

    /** Seed tenant from 004-seed-demo-tenant — used until request-scoped multi-tenancy lands. */
    static final String DEFAULT_TENANT_ID = "t-demo";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JpaPersistenceAdapter(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public DefinitionRecord save(DefinitionRecord d) {
        String tenantId = DEFAULT_TENANT_ID;
        String deploymentId = ensureRuntimeDeployment(tenantId);
        // previous latest for this key → not latest anymore
        jdbc.update("""
                update process_definition set is_latest=false
                where tenant_id=? and process_key=? and is_latest=true
                """, tenantId, d.key());
        jdbc.update("""
                insert into process_definition(
                  id,tenant_id,deployment_id,process_key,name,version,source_format,bpmn_xml,
                  bpmn_checksum,is_latest,status,created_at)
                values(?,?,?,?,?,?,?,?,?,true,'ACTIVE',?)
                """,
                d.id(), tenantId, deploymentId, d.key(), d.name(), d.version(), d.sourceFormat(), d.bpmnXml(),
                d.checksum(), Timestamp.from(d.createdAt()));
        return d;
    }

    @Override
    public Optional<DefinitionRecord> findDefinitionById(String id) {
        return oneDefinition("where id=?", id);
    }

    @Override
    public Optional<DefinitionRecord> findLatestByKey(String key) {
        return oneDefinition("where process_key=? and is_latest=true limit 1", key)
                .or(() -> oneDefinition("where process_key=? order by version desc limit 1", key));
    }

    @Override
    public Optional<DefinitionRecord> findByKeyAndVersion(String key, int version) {
        return oneDefinition("where process_key=? and version=?", key, version);
    }

    @Override
    public List<DefinitionRecord> findAll() {
        return jdbc.query("""
                select id,process_key,name,version,source_format,bpmn_xml,created_at,bpmn_checksum,is_latest
                from process_definition order by process_key,version
                """, (r, n) -> mapDefinition(r));
    }

    @Override
    public List<DefinitionVersionView> findVersionsByKey(String key) {
        return jdbc.query("""
                select pd.id, pd.process_key, pd.name, pd.version, pd.is_latest, pd.bpmn_checksum,
                       pd.created_at, pd.status,
                       (select count(*) from process_instance pi
                         where pi.definition_id = pd.id
                           and pi.status in ('RUNNING','WAITING','SUSPENDED')) as running_instances
                from process_definition pd
                where pd.process_key=?
                order by pd.version
                """, (r, n) -> new DefinitionVersionView(
                r.getString(1), r.getString(2), r.getString(3), r.getInt(4), r.getBoolean(5),
                r.getString(6), r.getTimestamp(7).toInstant(), r.getString(8), r.getLong(9)), key);
    }

    @Override
    public int nextVersion(String key) {
        Integer v = jdbc.queryForObject(
                """
                select coalesce(max(version),0)+1 from process_definition
                where tenant_id=? and process_key=?
                """,
                Integer.class, DEFAULT_TENANT_ID, key);
        return v == null ? 1 : v;
    }

    private Optional<DefinitionRecord> oneDefinition(String suffix, Object... args) {
        return jdbc.query("""
                select id,process_key,name,version,source_format,bpmn_xml,created_at,bpmn_checksum,is_latest
                from process_definition
                """ + suffix, (r, n) -> mapDefinition(r), args)
                .stream().findFirst();
    }

    private static DefinitionRecord mapDefinition(java.sql.ResultSet r) throws java.sql.SQLException {
        return new DefinitionRecord(
                r.getString(1), r.getString(2), r.getString(3), r.getInt(4), r.getString(5),
                r.getString(6), r.getTimestamp(7).toInstant(), r.getString(8), r.getBoolean(9));
    }

    @Override
    public InstanceRecord save(InstanceRecord i) {
        TenantDeployment td = tenantDeploymentForDefinition(i.definitionId());
        String defKey = i.definitionKey();
        Integer defVer = i.definitionVersion();
        if (defKey == null || defVer == null) {
            DefinitionRecord def = findDefinitionById(i.definitionId()).orElse(null);
            if (def != null) {
                if (defKey == null) {
                    defKey = def.key();
                }
                if (defVer == null) {
                    defVer = def.version();
                }
            }
        }
        jdbc.update("""
                insert into process_instance(
                  id,tenant_id,definition_id,deployment_id,business_key,status,created_at,ended_at,created_by,
                  parent_instance_id,root_instance_id,definition_key,definition_version)
                values(?,?,?,?,?,?,?,?,?,?,?,?,?)
                on conflict(id) do update set status=excluded.status, ended_at=excluded.ended_at,
                  created_by=coalesce(excluded.created_by, process_instance.created_by),
                  parent_instance_id=coalesce(excluded.parent_instance_id, process_instance.parent_instance_id),
                  root_instance_id=coalesce(excluded.root_instance_id, process_instance.root_instance_id),
                  definition_key=coalesce(excluded.definition_key, process_instance.definition_key),
                  definition_version=coalesce(excluded.definition_version, process_instance.definition_version)
                """,
                i.id(), td.tenantId(), i.definitionId(), td.deploymentId(), i.businessKey(), i.status().name(),
                Timestamp.from(i.createdAt()),
                i.endedAt() == null ? null : Timestamp.from(i.endedAt()),
                i.createdBy(), i.parentInstanceId(), i.rootInstanceId(), defKey, defVer);
        return new InstanceRecord(
                i.id(), i.definitionId(), i.businessKey(), i.status(), i.createdAt(), i.endedAt(),
                i.createdBy(), i.parentInstanceId(), i.rootInstanceId(), defKey, defVer);
    }

    @Override
    public Optional<InstanceRecord> findInstanceById(String id) {
        return jdbc.query("""
                select id,definition_id,business_key,status,created_at,ended_at,created_by,
                       parent_instance_id,root_instance_id,definition_key,definition_version
                from process_instance where id=?
                """, (r, n) -> new InstanceRecord(
                r.getString(1), r.getString(2), r.getString(3), InstanceStatus.valueOf(r.getString(4)),
                r.getTimestamp(5).toInstant(),
                r.getTimestamp(6) == null ? null : r.getTimestamp(6).toInstant(),
                r.getString(7), r.getString(8), r.getString(9), r.getString(10),
                r.getObject(11) == null ? null : r.getInt(11)), id)
                .stream().findFirst();
    }

    @Override
    public TokenRecord save(TokenRecord t) {
        jdbc.update("""
                insert into execution_token(id,instance_id,current_node_id,status,parent_multi_instance_id)
                values(?,?,?,?,?)
                on conflict(id) do update set current_node_id=excluded.current_node_id, status=excluded.status
                """,
                t.id(), t.instanceId(), t.currentNodeId(), t.status().name(), t.parentMultiInstanceId());
        return t;
    }

    @Override
    public List<TokenRecord> findByInstanceId(String id) {
        return jdbc.query("""
                select id,instance_id,current_node_id,status,parent_multi_instance_id
                from execution_token where instance_id=?
                """, (r, n) -> new TokenRecord(
                r.getString(1), r.getString(2), r.getString(3), TokenStatus.valueOf(r.getString(4)), r.getString(5)), id);
    }

    @Override
    public Optional<TokenRecord> findTokenById(String id) {
        return jdbc.query("""
                select id,instance_id,current_node_id,status,parent_multi_instance_id
                from execution_token where id=?
                """, (r, n) -> new TokenRecord(
                r.getString(1), r.getString(2), r.getString(3), TokenStatus.valueOf(r.getString(4)), r.getString(5)), id)
                .stream().findFirst();
    }

    @Override
    public void putAll(String id, Map<String, Object> values) {
        values.forEach((k, v) -> {
            try {
                if (v == null) {
                    jdbc.update("""
                            insert into token_variable(instance_id,name,type,value_text,value_json)
                            values(?,?,?,?,cast(? as jsonb))
                            on conflict(instance_id,name) do update
                            set type=excluded.type, value_text=excluded.value_text, value_json=excluded.value_json,
                                updated_at=now()
                            """, id, k, "STRING", null, null);
                    return;
                }
                String type = typeOf(v);
                String dbType = type.toUpperCase(java.util.Locale.ROOT);
                String text = "json".equals(type) ? null : String.valueOf(v);
                String valueJson = "json".equals(type) ? json.writeValueAsString(v) : null;
                jdbc.update("""
                        insert into token_variable(instance_id,name,type,value_text,value_json)
                        values(?,?,?,?,cast(? as jsonb))
                        on conflict(instance_id,name) do update
                        set type=excluded.type, value_text=excluded.value_text, value_json=excluded.value_json,
                            updated_at=now()
                        """, id, k, dbType, text, valueJson);
            } catch (Exception e) {
                throw new IllegalArgumentException("Cannot store variable " + k, e);
            }
        });
    }

    @Override
    public Map<String, Object> getAll(String id) {
        Map<String, Object> result = new HashMap<>();
        jdbc.query("select name,type,value_text,value_json::text from token_variable where instance_id=?", rs -> {
            try {
                result.put(rs.getString(1), decode(rs.getString(2), rs.getString(3), rs.getString(4), json));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, id);
        return result;
    }

    @Override
    public UserTaskRecord save(UserTaskRecord t) {
        String tenantId = tenantForInstance(t.instanceId());
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    insert into user_task(
                      id,tenant_id,instance_id,token_id,node_id,name,
                      assignee,candidate_groups,candidate_users,due_date,priority,form_key,
                      submitted_data,claim_time,completed,created_at,completed_at)
                    values(?,?,?,?,?,?,?,?,?,?,?,?,cast(? as jsonb),?,?,?,?)
                    on conflict(id) do update set
                      assignee=excluded.assignee,
                      candidate_groups=excluded.candidate_groups,
                      candidate_users=excluded.candidate_users,
                      due_date=excluded.due_date,
                      priority=excluded.priority,
                      form_key=excluded.form_key,
                      submitted_data=excluded.submitted_data,
                      claim_time=excluded.claim_time,
                      completed=excluded.completed,
                      completed_at=excluded.completed_at
                    """);
            int i = 1;
            ps.setString(i++, t.id());
            ps.setString(i++, tenantId);
            ps.setString(i++, t.instanceId());
            ps.setString(i++, t.tokenId());
            ps.setString(i++, t.nodeId());
            ps.setString(i++, t.name());
            ps.setString(i++, t.assignee());
            setPgTextArray(ps, i++, con, t.candidateGroups());
            setPgTextArray(ps, i++, con, t.candidateUsers());
            if (t.dueDate() == null) {
                ps.setTimestamp(i++, null);
            } else {
                ps.setTimestamp(i++, Timestamp.from(t.dueDate()));
            }
            ps.setInt(i++, t.priority());
            ps.setString(i++, t.formKey());
            ps.setString(i++, t.submittedData());
            if (t.claimTime() == null) {
                ps.setTimestamp(i++, null);
            } else {
                ps.setTimestamp(i++, Timestamp.from(t.claimTime()));
            }
            ps.setBoolean(i++, t.completed());
            ps.setTimestamp(i++, Timestamp.from(t.createdAt()));
            if (t.completedAt() == null) {
                ps.setTimestamp(i, null);
            } else {
                ps.setTimestamp(i, Timestamp.from(t.completedAt()));
            }
            return ps;
        });
        return t;
    }

    @Override
    public Optional<UserTaskRecord> findTaskById(String id) {
        return jdbc.query("""
                select id,instance_id,token_id,node_id,name,assignee,
                       candidate_groups,candidate_users,due_date,priority,form_key,
                       submitted_data::text,claim_time,completed,created_at,completed_at
                from user_task where id=?
                """, (r, n) -> new UserTaskRecord(
                r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5),
                r.getString(6),
                readPgTextArray(r.getArray(7)),
                readPgTextArray(r.getArray(8)),
                r.getTimestamp(9) == null ? null : r.getTimestamp(9).toInstant(),
                r.getInt(10),
                r.getString(11),
                r.getString(12),
                r.getTimestamp(13) == null ? null : r.getTimestamp(13).toInstant(),
                r.getBoolean(14),
                r.getTimestamp(15).toInstant(),
                r.getTimestamp(16) == null ? null : r.getTimestamp(16).toInstant()), id)
                .stream().findFirst();
    }

    @Override
    public void completeOpenTasks(String instanceId, Instant at) {
        jdbc.update("""
                update user_task set completed=true, completed_at=?
                 where instance_id=? and completed=false
                """, Timestamp.from(at), instanceId);
    }

    @Override
    public void completeOpenTaskForToken(String tokenId, Instant at) {
        jdbc.update("""
                update user_task set completed=true, completed_at=?
                 where token_id=? and completed=false
                """, Timestamp.from(at), tokenId);
    }

    private static void setPgTextArray(java.sql.PreparedStatement ps, int index, java.sql.Connection con, List<String> values)
            throws java.sql.SQLException {
        if (values == null || values.isEmpty()) {
            ps.setNull(index, java.sql.Types.ARRAY);
            return;
        }
        ps.setArray(index, con.createArrayOf("varchar", values.toArray()));
    }

    private static List<String> readPgTextArray(java.sql.Array array) throws java.sql.SQLException {
        if (array == null) {
            return List.of();
        }
        Object raw = array.getArray();
        if (raw instanceof String[] strings) {
            return List.of(strings);
        }
        if (raw instanceof Object[] objects) {
            List<String> out = new java.util.ArrayList<>();
            for (Object o : objects) {
                if (o != null) {
                    out.add(String.valueOf(o));
                }
            }
            return List.copyOf(out);
        }
        return List.of();
    }

    @Override
    public JobRecord save(JobRecord job) {
        String tenantId = tenantForInstance(job.instanceId());
        jdbc.update("""
                insert into job(id,tenant_id,instance_id,token_id,type,payload,status,attempts,run_at)
                values(?,?,?,?,?,cast(? as jsonb),?,?,?)
                on conflict(id) do update set status=excluded.status, attempts=excluded.attempts
                """,
                job.id(), tenantId, job.instanceId(), job.tokenId(), job.type(), job.payload(),
                job.status().name(), job.attempts(), Timestamp.from(job.runAt()));
        return job;
    }

    @Override
    public Optional<JobRecord> findJobById(String id) {
        return jdbc.query("""
                select id,instance_id,token_id,type,payload::text,status,attempts,run_at from job where id=?
                """, (r, n) -> mapJob(r), id)
                .stream().findFirst();
    }

    @Override
    public List<JobRecord> findPendingByInstance(String instanceId) {
        return jdbc.query("""
                select id,instance_id,token_id,type,payload::text,status,attempts,run_at
                from job where instance_id=? and status='PENDING' order by run_at
                """, (r, n) -> mapJob(r), instanceId);
    }

    private static JobRecord mapJob(java.sql.ResultSet r) throws java.sql.SQLException {
        return new JobRecord(
                r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5),
                JobStatus.valueOf(r.getString(6)), r.getInt(7), r.getTimestamp(8).toInstant());
    }

    /**
     * Canonical EAV type map (plan 16). Fractional numbers must NOT be stored as {@code long}.
     */
    static String typeOf(Object o) {
        if (o == null) {
            return "string";
        }
        if (o instanceof Double || o instanceof Float || o instanceof java.math.BigDecimal) {
            return "double";
        }
        if (o instanceof Number) {
            return "long";
        }
        if (o instanceof Boolean) {
            return "boolean";
        }
        if (o instanceof java.time.temporal.TemporalAccessor) {
            return "date";
        }
        return o instanceof String ? "string" : "json";
    }

    /** Inverse of {@link #typeOf(Object)} for token_variable rows. Never throws NumberFormatException on decimals. */
    static Object decode(String type, String valueText, String valueJson, ObjectMapper mapper) throws Exception {
        if (type == null) {
            return valueText;
        }
        // DB stores UPPERCASE (v3 CHECK); accept either case.
        return switch (type.toLowerCase(java.util.Locale.ROOT)) {
            case "long" -> valueText == null ? null : Long.valueOf(valueText);
            case "double" -> valueText == null ? null : new java.math.BigDecimal(valueText);
            case "boolean" -> valueText == null ? null : Boolean.valueOf(valueText);
            case "json" -> valueJson == null ? null : mapper.readValue(valueJson, new TypeReference<>() {});
            case "date", "string" -> valueText;
            default -> valueText;
        };
    }

    private record TenantDeployment(String tenantId, String deploymentId) {}

    private String ensureRuntimeDeployment(String tenantId) {
        List<String> existing = jdbc.query("""
                select id from process_deployment
                where tenant_id=? and source='API'
                order by deployed_at desc limit 1
                """, (r, n) -> r.getString(1), tenantId);
        if (!existing.isEmpty()) {
            return existing.getFirst();
        }
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                insert into process_deployment(id,tenant_id,name,source)
                values(?,?,?, 'API')
                """, id, tenantId, "runtime-deploys");
        return id;
    }

    private TenantDeployment tenantDeploymentForDefinition(String definitionId) {
        List<TenantDeployment> rows = jdbc.query("""
                select tenant_id, deployment_id from process_definition where id=?
                """, (r, n) -> new TenantDeployment(r.getString(1), r.getString(2)), definitionId);
        if (rows.isEmpty()) {
            throw new IllegalStateException("Definition not found for instance insert: " + definitionId);
        }
        return rows.getFirst();
    }

    private String tenantForInstance(String instanceId) {
        List<String> rows = jdbc.query(
                "select tenant_id from process_instance where id=?",
                (r, n) -> r.getString(1), instanceId);
        if (!rows.isEmpty() && rows.getFirst() != null) {
            return rows.getFirst();
        }
        // Fallback for orphaned queue messages after schema reset — still must satisfy FK to tenant.
        return DEFAULT_TENANT_ID;
    }

    @Override
    public String enter(String tokenId, String instanceId, String nodeId, String nodeType, Instant enteredAt) {
        String id = UUID.randomUUID().toString();
        Integer maxSeq = jdbc.queryForObject(
                "select coalesce(max(sequence_no),0) from execution_token_state where token_id=?",
                Integer.class, tokenId);
        int seq = (maxSeq == null ? 0 : maxSeq) + 1;
        jdbc.update("""
                insert into execution_token_state(id,token_id,instance_id,node_id,node_type,sequence_no,status,entered_at)
                values(?,?,?,?,?,?,'ACTIVE',?)
                """,
                id, tokenId, instanceId, nodeId, nodeType, seq, Timestamp.from(enteredAt));
        return id;
    }

    @Override
    public void exit(String tokenStateId, String status, Instant exitedAt, Integer durationMs, String errorMessage) {
        jdbc.update("""
                update execution_token_state
                set status=?, exited_at=?, duration_ms=?, error_message=?
                where id=?
                """,
                status, Timestamp.from(exitedAt), durationMs, errorMessage, tokenStateId);
    }

    @Override
    public Optional<String> activeStateId(String tokenId, String nodeId) {
        return jdbc.query("""
                select id from execution_token_state
                where token_id=? and node_id=? and status='ACTIVE'
                order by sequence_no desc limit 1
                """, (r, n) -> r.getString(1), tokenId, nodeId)
                .stream().findFirst();
    }

    @Override
    public void log(ListenerLogEntry entry) {
        jdbc.update("""
                insert into execution_listener_log(
                    id, token_state_id, instance_id, node_id, phase, listener_index,
                    listener_type, listener_ref, status, started_at, ended_at, error_message)
                values(?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                UUID.randomUUID().toString(), entry.tokenStateId(), entry.instanceId(), entry.nodeId(),
                entry.phase(), entry.listenerIndex(), entry.listenerType(), entry.listenerRef(), entry.status(),
                Timestamp.from(entry.startedAt()),
                entry.endedAt() == null ? null : Timestamp.from(entry.endedAt()),
                entry.errorMessage());
    }

    // ---------------------------------------------------------------------
    // InstanceControlPort (plan 27) — terminate / suspend / resume + guardrail reads
    // ---------------------------------------------------------------------

    @Override
    public String statusOf(String instanceId) {
        List<String> rows = jdbc.query(
                "select status from process_instance where id=?",
                (r, n) -> r.getString(1), instanceId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Override
    public boolean isHalted(String instanceId) {
        String status = statusOf(instanceId);
        return "TERMINATED".equals(status) || "SUSPENDED".equals(status);
    }

    @Override
    public List<String> instanceTree(String rootId) {
        return jdbc.query("""
                with recursive tree as (
                    select id from process_instance where id=?
                    union all
                    select c.id from process_instance c join tree t on c.parent_instance_id = t.id
                )
                select id from tree
                """, (r, n) -> r.getString(1), rootId);
    }

    @Override
    public void terminate(String instanceId, String user, String reason) {
        // 0) lock the row so a parallel terminate/step can't race
        List<String> locked = jdbc.query(
                "select status from process_instance where id=? for update",
                (r, n) -> r.getString(1), instanceId);
        if (locked.isEmpty()) {
            return; // unknown instance → no-op
        }
        String status = locked.getFirst();
        if ("TERMINATED".equals(status) || "COMPLETED".equals(status)) {
            return; // idempotent: already in a final state
        }

        // 1) instance first — a parallel consumer reads TERMINATED and stops
        jdbc.update("""
                update process_instance
                   set status='TERMINATED', terminated_by=?, cancel_reason=?, ended_at=now()
                 where id=? and status in ('RUNNING','WAITING','SUSPENDED','FAILED')
                """, user, reason, instanceId);

        // 2) live tokens
        jdbc.update("""
                update execution_token set status='CANCELED'
                 where instance_id=? and status in ('ACTIVE','WAITING','WAITING_JOB')
                """, instanceId);

        // 3) open steps
        jdbc.update("""
                update execution_token_state set status='CANCELED', exited_at=now()
                 where instance_id=? and status='ACTIVE'
                """, instanceId);

        // 4) queued jobs — the next step must not run
        jdbc.update("""
                update job set status='CANCELED'
                 where instance_id=? and status in ('PENDING','RETRY','LOCKED')
                """, instanceId);

        // 5) timer/message/signal subscriptions — never wake this instance again
        jdbc.update("delete from event_subscription where instance_id=?", instanceId);

        // 6) external tasks
        jdbc.update("""
                update external_task set status='FAILED'
                 where instance_id=? and status in ('CREATED','LOCKED')
                """, instanceId);

        // 7) open user tasks
        jdbc.update("""
                update user_task set completed=true, completed_at=now()
                 where instance_id=? and completed=false
                """, instanceId);

        // 8) open incidents
        jdbc.update("""
                update incident set status='RESOLVED', resolved_at=now(), resolved_by=?
                 where instance_id=? and status='OPEN'
                """, user, instanceId);

        // 9) audit
        writeAudit(instanceId, "INSTANCE_TERMINATED", user, reason);
    }

    @Override
    public void suspend(String instanceId, String user, String reason) {
        List<String> locked = jdbc.query(
                "select status from process_instance where id=? for update",
                (r, n) -> r.getString(1), instanceId);
        if (locked.isEmpty()) {
            return;
        }
        String status = locked.getFirst();
        if (!"RUNNING".equals(status) && !"WAITING".equals(status) && !"FAILED".equals(status)) {
            return; // only a live instance can be suspended (idempotent otherwise)
        }
        jdbc.update("""
                update process_instance set status='SUSPENDED'
                 where id=? and status in ('RUNNING','WAITING','FAILED')
                """, instanceId);
        writeAudit(instanceId, "INSTANCE_SUSPENDED", user, reason);
    }

    @Override
    public void resume(String instanceId) {
        jdbc.update("""
                update process_instance set status='RUNNING'
                 where id=? and status='SUSPENDED'
                """, instanceId);
        writeAudit(instanceId, "INSTANCE_RESUMED", null, null);
    }

    @Override
    public int maxNodeRevisitCount(String instanceId) {
        Integer v = jdbc.queryForObject("""
                select coalesce(max(c),0) from (
                    select count(*) c from execution_token_state
                    where instance_id=? group by node_id
                ) s
                """, Integer.class, instanceId);
        return v == null ? 0 : v;
    }

    @Override
    public int tokenStateCount(String instanceId) {
        Integer v = jdbc.queryForObject(
                "select count(*) from execution_token_state where instance_id=?",
                Integer.class, instanceId);
        return v == null ? 0 : v;
    }

    @Override
    public int subprocessDepth(String instanceId) {
        Integer v = jdbc.queryForObject("""
                with recursive chain as (
                    select id, parent_instance_id, 1 as depth
                    from process_instance where id=?
                    union all
                    select p.id, p.parent_instance_id, c.depth+1
                    from process_instance p join chain c on p.id = c.parent_instance_id
                )
                select coalesce(max(depth),1) from chain
                """, Integer.class, instanceId);
        return v == null ? 1 : v;
    }

    @Override
    public int spawnCountUnderRoot(String rootId) {
        Integer v = jdbc.queryForObject(
                "select count(*) from process_instance where root_instance_id=? or id=?",
                Integer.class, rootId, rootId);
        return v == null ? 0 : v;
    }

    private void writeAudit(String instanceId, String eventType, String user, String reason) {
        String details;
        try {
            Map<String, Object> d = new HashMap<>();
            if (user != null) {
                d.put("by", user);
            }
            if (reason != null) {
                d.put("reason", reason);
            }
            details = json.writeValueAsString(d);
        } catch (Exception e) {
            details = "{}";
        }
        jdbc.update("""
                insert into execution_log(id, instance_id, event_type, status, message, details, created_at)
                values(gen_random_uuid()::text, ?, ?, 'OK', ?, cast(? as jsonb), now())
                """, instanceId, eventType, reason, details);
    }

    // ---------------------------------------------------------------------
    // IncidentPort (plan 25/27)
    // ---------------------------------------------------------------------

    @Override
    public String raise(
            String instanceId, String tokenId, String tokenStateId,
            String type, String severity, String message
    ) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                insert into incident(
                  id, tenant_id, instance_id, token_id, token_state_id, type, severity, status, message)
                select ?, pi.tenant_id, ?, ?, ?, ?, ?, 'OPEN', ?
                from process_instance pi where pi.id=?
                """,
                id, instanceId, tokenId, tokenStateId, type,
                severity == null ? "ERROR" : severity, message, instanceId);
        return id;
    }

    // ---------------------------------------------------------------------
    // EventSubscriptionPort (plan 32 Phase 2) — TIMER/MESSAGE/SIGNAL waits
    // ---------------------------------------------------------------------

    @Override
    public EventSubscriptionRecord save(EventSubscriptionRecord sub) {
        String tenantId = tenantForInstance(sub.instanceId());
        jdbc.update("""
                insert into event_subscription(
                  id, tenant_id, instance_id, token_id, type, event_name, node_id, configuration, created_at)
                values(?,?,?,?,?,?,?,cast(? as jsonb),?)
                on conflict(id) do update set
                  event_name=excluded.event_name, node_id=excluded.node_id, configuration=excluded.configuration
                """,
                sub.id(), tenantId, sub.instanceId(), sub.tokenId(), sub.type(), sub.eventName(), sub.nodeId(),
                sub.configJson(), Timestamp.from(sub.createdAt()));
        return sub;
    }

    @Override
    public void deleteById(String id) {
        jdbc.update("delete from event_subscription where id=?", id);
    }

    @Override
    public void deleteByInstanceId(String instanceId) {
        jdbc.update("delete from event_subscription where instance_id=?", instanceId);
    }

    @Override
    public void deleteByInstanceAndNode(String instanceId, String nodeId) {
        jdbc.update("delete from event_subscription where instance_id=? and node_id=?", instanceId, nodeId);
    }

    @Override
    public Optional<EventSubscriptionRecord> findById(String id) {
        return jdbc.query("""
                select id,instance_id,token_id,type,event_name,node_id,configuration::text,created_at
                from event_subscription where id=?
                """, (r, n) -> mapEventSubscription(r), id)
                .stream().findFirst();
    }

    @Override
    public List<EventSubscriptionRecord> findSubscriptionsByInstanceId(String instanceId) {
        return jdbc.query("""
                select id,instance_id,token_id,type,event_name,node_id,configuration::text,created_at
                from event_subscription where instance_id=?
                """, (r, n) -> mapEventSubscription(r), instanceId);
    }

    @Override
    public List<EventSubscriptionRecord> findOpenByTypeAndName(String type, String eventName) {
        return jdbc.query("""
                select es.id,es.instance_id,es.token_id,es.type,es.event_name,es.node_id,es.configuration::text,es.created_at
                from event_subscription es
                join process_instance pi on pi.id = es.instance_id
                where es.type=? and es.event_name=? and pi.status in ('RUNNING','WAITING')
                """, (r, n) -> mapEventSubscription(r), type, eventName);
    }

    private static EventSubscriptionRecord mapEventSubscription(java.sql.ResultSet r) throws java.sql.SQLException {
        return new EventSubscriptionRecord(
                r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5),
                r.getString(6), r.getString(7), r.getTimestamp(8).toInstant());
    }
}
