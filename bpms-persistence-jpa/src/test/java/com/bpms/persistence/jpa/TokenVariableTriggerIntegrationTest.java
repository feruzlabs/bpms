package com.bpms.persistence.jpa;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plan 25 / 29: {@code fn_token_variable_history} trigger archives old values and bumps revision.
 */
class TokenVariableTriggerIntegrationTest {

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = PostgresLiquibaseTestSupport.jdbc();
        jdbc.update("delete from token_variable_history");
        jdbc.update("delete from token_variable");
        jdbc.update("""
                delete from process_instance where id like 'tv-%'
                """);
        insertInstance("tv-inst");
    }

    @Test
    void updateArchivesOldValueAndIncrementsRevision() {
        jdbc.update("""
                insert into token_variable(id, instance_id, name, type, value_text, revision)
                values('tv-var', 'tv-inst', 'amount', 'STRING', '620', 0)
                """);
        jdbc.update("""
                update token_variable set value_text='655' where id='tv-var'
                """);

        Map<String, Object> current = jdbc.queryForMap(
                "select value_text, revision from token_variable where id='tv-var'");
        assertEquals("655", current.get("value_text"));
        assertEquals(1, ((Number) current.get("revision")).intValue());

        List<Map<String, Object>> history = jdbc.queryForList("""
                select old_value_text, revision from token_variable_history where variable_id='tv-var'
                """);
        assertEquals(1, history.size());
        assertEquals("620", history.getFirst().get("old_value_text"));
        assertEquals(0, ((Number) history.getFirst().get("revision")).intValue());
    }

    @Test
    void unchangedUpdateDoesNotWriteHistory() {
        jdbc.update("""
                insert into token_variable(id, instance_id, name, type, value_text, revision)
                values('tv-var2', 'tv-inst', 'limit', 'STRING', '620', 0)
                """);
        jdbc.update("""
                update token_variable set value_text='620' where id='tv-var2'
                """);

        Integer revision = jdbc.queryForObject(
                "select revision from token_variable where id='tv-var2'", Integer.class);
        assertEquals(0, revision);

        Integer historyCount = jdbc.queryForObject(
                "select count(*) from token_variable_history where variable_id='tv-var2'", Integer.class);
        assertEquals(0, historyCount);
    }

    private void insertInstance(String id) {
        jdbc.update("""
                insert into process_instance(
                    id, tenant_id, definition_id, deployment_id, business_key, status, created_at)
                values (?, 't-demo', 'pd-credit-1', 'dep-credit-1', ?, 'RUNNING', now())
                on conflict (id) do nothing
                """, id, id + "-bk");
    }
}
