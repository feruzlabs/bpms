package com.bpms.persistence.jpa;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Plan 27 / 29: cascade terminate + idempotent terminate against real schema.
 */
class InstanceControlPersistenceTest {

    private JdbcTemplate jdbc;
    private JpaPersistenceAdapter adapter;

    @BeforeEach
    void setUp() {
        jdbc = PostgresLiquibaseTestSupport.jdbc();
        adapter = new JpaPersistenceAdapter(jdbc, new ObjectMapper());
        cleanup("ic-%");
    }

    @Test
    void cascadeTerminateClosesRootAndDescendants() {
        insertInstance("ic-root", null, "ic-root");
        insertInstance("ic-c1", "ic-root", "ic-root");
        insertInstance("ic-c2", "ic-c1", "ic-root");

        jdbc.update("""
                insert into execution_token(id, instance_id, current_node_id, status)
                values ('ic-tok-root', 'ic-root', 'nodeA', 'ACTIVE'),
                       ('ic-tok-c1', 'ic-c1', 'nodeB', 'ACTIVE')
                """);
        jdbc.update("""
                insert into event_subscription(id, tenant_id, instance_id, type, event_name)
                values ('ic-sub', 't-demo', 'ic-root', 'TIMER', 'poll')
                """);

        List<String> tree = adapter.instanceTree("ic-root");
        for (int i = tree.size() - 1; i >= 0; i--) {
            adapter.terminate(tree.get(i), "tester", "cascade");
        }

        assertEquals(3, jdbc.queryForObject(
                "select count(*) from process_instance where id like 'ic-%' and status='TERMINATED'",
                Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from execution_token where instance_id like 'ic-%' and status != 'CANCELED'",
                Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from event_subscription where instance_id like 'ic-%'", Integer.class));
    }

    @Test
    void terminateIsIdempotent() {
        insertInstance("ic-idem", null, "ic-idem");
        jdbc.update("""
                insert into execution_token(id, instance_id, current_node_id, status)
                values ('ic-tok-idem', 'ic-idem', 'nodeA', 'ACTIVE')
                """);

        adapter.terminate("ic-idem", "tester", "once");
        adapter.terminate("ic-idem", "tester", "twice");

        assertEquals("TERMINATED", adapter.statusOf("ic-idem"));
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from execution_log where instance_id='ic-idem' and event_type='INSTANCE_TERMINATED'",
                Integer.class));
        assertEquals("tester", jdbc.queryForObject(
                "select terminated_by from process_instance where id='ic-idem'", String.class));
    }

    private void insertInstance(String id, String parentId, String rootId) {
        jdbc.update("""
                insert into process_instance(
                    id, tenant_id, definition_id, deployment_id, business_key,
                    parent_instance_id, root_instance_id, status, created_at)
                values (?, 't-demo', 'pd-credit-1', 'dep-credit-1', ?,
                        ?, ?, 'RUNNING', now())
                """, id, id + "-bk", parentId, rootId);
    }

    private void cleanup(String prefix) {
        jdbc.update("delete from execution_log where instance_id like ?", prefix);
        jdbc.update("delete from event_subscription where instance_id like ?", prefix);
        jdbc.update("delete from execution_token where instance_id like ?", prefix);
        jdbc.update("delete from process_instance where id like ? and parent_instance_id is not null", prefix);
        jdbc.update("delete from process_instance where id like ?", prefix);
    }
}
