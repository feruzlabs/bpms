package com.bpms.expression;

import com.bpms.spi.expression.ExpressionEvaluator;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Resolves Camunda userTask assignee / dueDate / candidates with old-engine parity for
 * {@code TYPE__$var__prop} (e.g. {@code EMPLOYEE__$employee__empId}).
 */
public final class HumanTaskExpressions {

    private HumanTaskExpressions() {}

    /**
     * Old {@code TasksUserAssigneeService.create} parity: split on first {@code __};
     * type + id expression are evaluated; result stored as {@code TYPE__resolvedId}.
     */
    public static String resolveAssignee(String raw, ExpressionEvaluator eval, Map<String, Object> vars) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] split = raw.split("__", 2);
        if (split.length > 1) {
            String type = stringify(evalValue(split[0], eval, vars), split[0]);
            String id = stringify(evalValue(split[1], eval, vars), split[1]);
            return type + "__" + id;
        }
        return stringify(evalValue(raw, eval, vars), raw);
    }

    public static Instant resolveDueDate(String raw, ExpressionEvaluator eval, Map<String, Object> vars) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return toInstant(evalValue(raw, eval, vars));
    }

    public static int resolvePriority(String raw, ExpressionEvaluator eval, Map<String, Object> vars) {
        if (raw == null || raw.isBlank()) {
            return 50;
        }
        Object value = evalValue(raw, eval, vars);
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return 50;
        }
    }

    public static List<String> resolveCsv(String raw, ExpressionEvaluator eval, Map<String, Object> vars) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            out.add(resolveAssignee(trimmed, eval, vars));
        }
        return List.copyOf(out);
    }

    /**
     * Evaluate Camunda task attributes. After {@code $VAR} → {@code VAR} normalization the SpEL
     * heuristic may treat the name as a literal — fall back to a direct variable lookup.
     */
    static Object evalValue(String raw, ExpressionEvaluator eval, Map<String, Object> vars) {
        String normalized = normalizeExpr(raw);
        if (vars != null && vars.containsKey(normalized)) {
            return vars.get(normalized);
        }
        Object value = eval.evaluate(normalized, vars);
        if (value == null && raw != null && !raw.equals(normalized)) {
            value = eval.evaluate(raw, vars);
        }
        if (value instanceof String s && vars != null && vars.containsKey(s)) {
            return vars.get(s);
        }
        return value;
    }

    /**
     * {@code $employee__empId} → {@code employee.empId}; {@code $TASK_EXPIRED_DATE} → {@code TASK_EXPIRED_DATE};
     * {@code ${x.y}} → {@code x.y}.
     */
    static String normalizeExpr(String expr) {
        if (expr == null) {
            return null;
        }
        String s = expr.trim();
        if (s.startsWith("${") && s.endsWith("}") && s.length() > 3) {
            return s.substring(2, s.length() - 1).trim();
        }
        if (s.startsWith("$") && s.length() > 1 && Character.isJavaIdentifierStart(s.charAt(1))) {
            s = s.substring(1);
        }
        if (s.contains("__")) {
            s = s.replace("__", ".");
        }
        return s;
    }

    private static String stringify(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String s = String.valueOf(value);
        return s.isBlank() ? fallback : s;
    }

    private static Instant toInstant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Instant i) {
            return i;
        }
        if (value instanceof java.util.Date d) {
            return d.toInstant();
        }
        if (value instanceof Number n) {
            long v = n.longValue();
            // seconds vs millis heuristic
            return Instant.ofEpochMilli(v < 1_000_000_000_000L ? v * 1000L : v);
        }
        String s = String.valueOf(value).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException ignored) {
            // continue
        }
        try {
            return LocalDateTime.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                    .toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            // continue
        }
        try {
            return LocalDate.parse(s).atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
