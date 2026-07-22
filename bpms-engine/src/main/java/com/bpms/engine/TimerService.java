package com.bpms.engine;

import com.bpms.core.definition.TimerEventDef;
import com.bpms.spi.expression.ExpressionEvaluator;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * Resolves a {@link TimerEventDef} (DURATION/DATE/CYCLE, ISO 8601) to a concrete fire {@link Instant}
 * (plan 32 Phase 2). Values are usually a literal ISO 8601 string (e.g. {@code PT1M}); when they are not
 * parseable as-is they are treated as a SpEL expression (Camunda's {@code ${...}} convention — the {@code
 * ${}} wrapper, if present, is stripped before evaluation) that must evaluate to an ISO 8601 string.
 */
public final class TimerService {

    private TimerService() {
    }

    /** {@code now} + the resolved duration/date — CYCLE is treated as a single fire-once wait for MVP (repeat count is ignored). */
    public static Instant resolveRunAt(
            TimerEventDef def, Instant now, Map<String, Object> vars, ExpressionEvaluator expressions
    ) {
        if (def == null || def.value() == null || def.value().isBlank()) {
            return now;
        }
        String raw = def.value().trim();
        return switch (def.kind()) {
            case DURATION -> now.plus(resolveDuration(raw, vars, expressions));
            case DATE -> resolveDate(raw, now, vars, expressions);
            case CYCLE -> now.plus(resolveCycleDuration(raw, vars, expressions));
        };
    }

    private static Duration resolveDuration(String raw, Map<String, Object> vars, ExpressionEvaluator expressions) {
        try {
            return Duration.parse(raw);
        } catch (DateTimeParseException notLiteral) {
            String evaluated = evaluateToString(raw, vars, expressions);
            return Duration.parse(evaluated);
        }
    }

    private static Instant resolveDate(String raw, Instant now, Map<String, Object> vars, ExpressionEvaluator expressions) {
        Instant parsed = tryParseInstant(raw);
        if (parsed != null) {
            return parsed;
        }
        String evaluated = evaluateToString(raw, vars, expressions);
        Instant fromEval = tryParseInstant(evaluated);
        if (fromEval != null) {
            return fromEval;
        }
        throw new IllegalArgumentException("Cannot parse timeDate value: " + raw);
    }

    private static Instant tryParseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            // fall through to offset-date-time (Instant.parse requires a 'Z'/offset in a specific form)
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    /**
     * {@code R3/PT10M} (repeat/interval) or plain {@code PT10M} — MVP takes only the interval and ignores
     * the repeat count (single fire-once job per plan 32 Phase 2; cron expressions are not supported).
     */
    private static Duration resolveCycleDuration(String raw, Map<String, Object> vars, ExpressionEvaluator expressions) {
        String interval = raw.contains("/") ? raw.substring(raw.indexOf('/') + 1) : raw;
        try {
            return Duration.parse(interval);
        } catch (DateTimeParseException notLiteral) {
            String evaluated = evaluateToString(interval, vars, expressions);
            return Duration.parse(evaluated);
        }
    }

    private static String evaluateToString(String expression, Map<String, Object> vars, ExpressionEvaluator expressions) {
        String stripped = stripExpressionMarkers(expression);
        Object result = expressions.evaluate(stripped, vars);
        if (result == null) {
            throw new IllegalArgumentException("Timer expression evaluated to null: " + expression);
        }
        return String.valueOf(result);
    }

    /** Camunda expressions are usually written as {@code ${expr}} or {@code #{expr}} — SpEL wants just {@code expr}. */
    private static String stripExpressionMarkers(String raw) {
        String value = raw.trim();
        if ((value.startsWith("${") || value.startsWith("#{")) && value.endsWith("}")) {
            return value.substring(2, value.length() - 1);
        }
        return value;
    }
}
