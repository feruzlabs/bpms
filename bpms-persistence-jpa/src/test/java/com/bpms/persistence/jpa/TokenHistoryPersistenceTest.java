package com.bpms.persistence.jpa;

import com.bpms.spi.engine.RuntimeModels.TokenRecord;
import com.bpms.spi.engine.RuntimeModels.TokenStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plan 23: execution_token_history (append-only) was replaced by execution_token_state
 * (insert-then-update, one row per node visit). save(TokenRecord) now only upserts execution_token —
 * the state lifecycle is driven explicitly by ExecutionEngine via TokenStatePort.enter/exit.
 */
class TokenHistoryPersistenceTest {

    @Test
    void saveTokenOnlyUpsertsExecutionToken() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        JpaPersistenceAdapter adapter = new JpaPersistenceAdapter(jdbc, new ObjectMapper());

        adapter.save(new TokenRecord("t1", "i1", "nodeA", TokenStatus.ACTIVE, null));
        adapter.save(new TokenRecord("t1", "i1", "nodeB", TokenStatus.COMPLETED, null));

        verify(jdbc, times(2)).update(anyString(), any(Object[].class));
    }

    @Test
    void enterInsertsActiveRowWithNextSequenceNumber() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(2);
        JpaPersistenceAdapter adapter = new JpaPersistenceAdapter(jdbc, new ObjectMapper());

        String id = adapter.enter("t1", "i1", "nodeA", "serviceTask", Instant.now());

        assertNotNull(id);
        verify(jdbc, times(1)).update(anyString(), any(Object[].class));
    }

    @Test
    void exitUpdatesTheSameRowInPlace() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        JpaPersistenceAdapter adapter = new JpaPersistenceAdapter(jdbc, new ObjectMapper());

        adapter.exit("state-1", "COMPLETED", Instant.now(), 42, null);

        verify(jdbc, times(1)).update(anyString(), any(Object[].class));
    }
}
