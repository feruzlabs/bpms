package com.bpms.expression;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HumanTaskExpressionsTest {

    private final SpelExpressionEvaluator eval = new SpelExpressionEvaluator();

    @Test
    void resolvesEmployeeDollarUnderscoreConvention() {
        Map<String, Object> vars = Map.of("employee", Map.of("empId", "E-42"));
        assertEquals("EMPLOYEE__E-42",
                HumanTaskExpressions.resolveAssignee("EMPLOYEE__$employee__empId", eval, vars));
    }

    @Test
    void resolvesDueDateFromVariable() {
        Instant due = Instant.parse("2026-07-22T10:00:00Z");
        Map<String, Object> vars = Map.of("TASK_EXPIRED_DATE", due);
        assertEquals(due, HumanTaskExpressions.resolveDueDate("$TASK_EXPIRED_DATE", eval, vars));
    }

    @Test
    void resolvesDueDateFromLocalDateString() {
        Map<String, Object> vars = Map.of("TASK_EXPIRED_DATE", "2026-08-01");
        Instant due = HumanTaskExpressions.resolveDueDate("$TASK_EXPIRED_DATE", eval, vars);
        assertEquals(LocalDate.of(2026, 8, 1).atStartOfDay().toInstant(java.time.ZoneOffset.UTC), due);
    }

    @Test
    void normalizeStripsTemplateAndDollarUnderscore() {
        assertEquals("employee.empId", HumanTaskExpressions.normalizeExpr("$employee__empId"));
        assertEquals("a.b", HumanTaskExpressions.normalizeExpr("${a.b}"));
        assertEquals("TASK_EXPIRED_DATE", HumanTaskExpressions.normalizeExpr("$TASK_EXPIRED_DATE"));
    }

    @Test
    void priorityDefaultsToFifty() {
        assertEquals(50, HumanTaskExpressions.resolvePriority(null, eval, Map.of()));
        assertEquals(1, HumanTaskExpressions.resolvePriority("1", eval, Map.of()));
    }

    @Test
    void modernJuelStyleAssignee() {
        Map<String, Object> vars = Map.of("employee", Map.of("empId", "E-9"));
        String resolved = HumanTaskExpressions.resolveAssignee("${employee.empId}", eval, vars);
        assertTrue(resolved.contains("E-9"));
    }
}
