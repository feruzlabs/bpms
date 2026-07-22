package com.bpms.expression;

import com.bpms.spi.expression.ExpressionEvaluator;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves Camunda-style {@code ${var}} string templates used in connector inputParameters
 * (plan 34 URLs like {@code https://…/latest/${base}}). Plain SpEL cannot parse those strings
 * (they contain {@code $}/{@code .}/{@code /}) and would return {@code null}.
 */
public final class TemplateExpressions {

    private static final Pattern TEMPLATE = Pattern.compile("\\$\\{([^}]+)}");

    private TemplateExpressions() {
    }

    /**
     * If {@code raw} contains {@code ${…}}, substitute from {@code vars}; otherwise delegate to SpEL
     * ({@link ExpressionEvaluator#evaluate}).
     */
    public static Object resolve(String raw, ExpressionEvaluator eval, Map<String, Object> vars) {
        if (raw != null && raw.contains("${")) {
            return interpolate(raw, vars);
        }
        return eval.evaluate(raw, vars);
    }

    public static String interpolate(String template, Map<String, Object> vars) {
        if (template == null) {
            return null;
        }
        Matcher m = TEMPLATE.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Object v = vars == null ? null : vars.get(m.group(1).trim());
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? "" : String.valueOf(v)));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
