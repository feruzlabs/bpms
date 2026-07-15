package com.bpms.persistence.jpa;

import com.bpms.spi.engine.RuntimeModels.*;
import com.bpms.spi.port.*;
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

/** PostgreSQL persistence behind SPI ports (Flyway-owned schema). */
@Repository
@Transactional
public class JpaPersistenceAdapter implements DefinitionRepositoryPort, InstanceRepositoryPort,
        TokenRepositoryPort, VariableStorePort, TaskRepositoryPort, JobRepositoryPort {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JpaPersistenceAdapter(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public DefinitionRecord save(DefinitionRecord d) {
        jdbc.update("""
                insert into process_definition(id,process_key,name,version,source_format,bpmn_xml,parsed_json,created_at)
                values(?,?,?,?,?,?,cast(? as jsonb),?)
                """,
                d.id(), d.key(), d.name(), d.version(), d.sourceFormat(), d.bpmnXml(), d.parsedJson(),
                Timestamp.from(d.createdAt()));
        return d;
    }

    @Override
    public Optional<DefinitionRecord> findDefinitionById(String id) {
        return oneDefinition("where id=?", id);
    }

    @Override
    public Optional<DefinitionRecord> findLatestByKey(String key) {
        return oneDefinition("where process_key=? order by version desc limit 1", key);
    }

    @Override
    public List<DefinitionRecord> findAll() {
        return jdbc.query("""
                select id,process_key,name,version,source_format,bpmn_xml,parsed_json::text,created_at
                from process_definition order by process_key,version
                """, (r, n) -> new DefinitionRecord(
                r.getString(1), r.getString(2), r.getString(3), r.getInt(4), r.getString(5),
                r.getString(6), r.getString(7), r.getTimestamp(8).toInstant()));
    }

    @Override
    public int nextVersion(String key) {
        Integer v = jdbc.queryForObject(
                "select coalesce(max(version),0)+1 from process_definition where process_key=?",
                Integer.class, key);
        return v == null ? 1 : v;
    }

    private Optional<DefinitionRecord> oneDefinition(String suffix, Object arg) {
        return jdbc.query("""
                select id,process_key,name,version,source_format,bpmn_xml,parsed_json::text,created_at
                from process_definition
                """ + suffix, (r, n) -> new DefinitionRecord(
                r.getString(1), r.getString(2), r.getString(3), r.getInt(4), r.getString(5),
                r.getString(6), r.getString(7), r.getTimestamp(8).toInstant()), arg)
                .stream().findFirst();
    }

    @Override
    public InstanceRecord save(InstanceRecord i) {
        jdbc.update("""
                insert into process_instance(id,definition_id,business_key,status,created_at,ended_at)
                values(?,?,?,?,?,?)
                on conflict(id) do update set status=excluded.status, ended_at=excluded.ended_at
                """,
                i.id(), i.definitionId(), i.businessKey(), i.status().name(),
                Timestamp.from(i.createdAt()),
                i.endedAt() == null ? null : Timestamp.from(i.endedAt()));
        return i;
    }

    @Override
    public Optional<InstanceRecord> findInstanceById(String id) {
        return jdbc.query("""
                select id,definition_id,business_key,status,created_at,ended_at from process_instance where id=?
                """, (r, n) -> new InstanceRecord(
                r.getString(1), r.getString(2), r.getString(3), InstanceStatus.valueOf(r.getString(4)),
                r.getTimestamp(5).toInstant(),
                r.getTimestamp(6) == null ? null : r.getTimestamp(6).toInstant()), id)
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
                String type = type(v);
                String text = "json".equals(type) ? null : String.valueOf(v);
                String valueJson = "json".equals(type) ? json.writeValueAsString(v) : null;
                jdbc.update("""
                        insert into token_variable(instance_id,name,type,value_text,value_json)
                        values(?,?,?,?,cast(? as jsonb))
                        on conflict(instance_id,name) do update
                        set type=excluded.type, value_text=excluded.value_text, value_json=excluded.value_json
                        """, id, k, type, text, valueJson);
            } catch (Exception e) {
                throw new IllegalArgumentException("Cannot store variable " + k, e);
            }
        });
    }

    @Override
    public Map<String, Object> getAll(String id) {
        Map<String, Object> result = new HashMap<>();
        jdbc.query("select name,type,value_text,value_json::text from token_variable where instance_id=?", rs -> {
            String t = rs.getString(2);
            String value = rs.getString(3);
            try {
                result.put(rs.getString(1), switch (t) {
                    case "long" -> Long.valueOf(value);
                    case "boolean" -> Boolean.valueOf(value);
                    case "json" -> json.readValue(rs.getString(4), new TypeReference<>() {});
                    default -> value;
                });
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, id);
        return result;
    }

    @Override
    public UserTaskRecord save(UserTaskRecord t) {
        jdbc.update("""
                insert into user_task(id,instance_id,token_id,node_id,name,completed,created_at,completed_at)
                values(?,?,?,?,?,?,?,?)
                on conflict(id) do update set completed=excluded.completed, completed_at=excluded.completed_at
                """,
                t.id(), t.instanceId(), t.tokenId(), t.nodeId(), t.name(), t.completed(),
                Timestamp.from(t.createdAt()),
                t.completedAt() == null ? null : Timestamp.from(t.completedAt()));
        return t;
    }

    @Override
    public Optional<UserTaskRecord> findTaskById(String id) {
        return jdbc.query("""
                select id,instance_id,token_id,node_id,name,completed,created_at,completed_at from user_task where id=?
                """, (r, n) -> new UserTaskRecord(
                r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getBoolean(6),
                r.getTimestamp(7).toInstant(),
                r.getTimestamp(8) == null ? null : r.getTimestamp(8).toInstant()), id)
                .stream().findFirst();
    }

    @Override
    public JobRecord save(JobRecord job) {
        jdbc.update("""
                insert into job(id,instance_id,token_id,type,payload,status,attempts,run_at)
                values(?,?,?,?,cast(? as jsonb),?,?,?)
                on conflict(id) do update set status=excluded.status, attempts=excluded.attempts
                """,
                job.id(), job.instanceId(), job.tokenId(), job.type(), job.payload(),
                job.status().name(), job.attempts(), Timestamp.from(job.runAt()));
        return job;
    }

    @Override
    public Optional<JobRecord> findJobById(String id) {
        return jdbc.query("""
                select id,instance_id,token_id,type,payload::text,status,attempts,run_at from job where id=?
                """, (r, n) -> new JobRecord(
                r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5),
                JobStatus.valueOf(r.getString(6)), r.getInt(7), r.getTimestamp(8).toInstant()), id)
                .stream().findFirst();
    }

    private String type(Object o) {
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
}
