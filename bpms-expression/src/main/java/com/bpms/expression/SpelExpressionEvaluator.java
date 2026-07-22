package com.bpms.expression;

import com.bpms.spi.expression.ExpressionEvaluator;
import com.bpms.spi.script.ScriptNamespaceProvider;
import org.springframework.context.expression.MapAccessor;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Old-engine parity: SpEL + {@link ExprStrHeuristic} + MapAccessor + error → null → false.
 * Script namespaces come from SPI contributors (no hardcoded hrms/labour).
 *
 * <p>Variable keys are also exposed under a {@code $name} alias (old
 * {@code InstanceTokenService.getValuesAsVars}), and {@code $name} tokens mentioned in the
 * expression but missing from the map are seeded as {@code null} so
 * {@code $sign == null} is true when the form field was omitted.
 */
public final class SpelExpressionEvaluator implements ExpressionEvaluator {

    private static final Pattern DOLLAR_VARS = Pattern.compile("(\\$([\\w]+))", Pattern.MULTILINE);

    private final ExpressionParser parser = new SpelExpressionParser();
    private final List<ScriptNamespaceProvider> namespaceProviders;

    public SpelExpressionEvaluator(List<ScriptNamespaceProvider> namespaceProviders) {
        this.namespaceProviders = namespaceProviders == null ? List.of() : List.copyOf(namespaceProviders);
    }

    public SpelExpressionEvaluator() {
        this(List.of());
    }

    @Override
    public Object evaluate(String expression, Map<String, Object> variables) {
        if (expression == null) {
            return null;
        }
        if (!ExprStrHeuristic.isExprStr(expression)) {
            return expression;
        }
        try {
            Map<String, Object> data = buildContext(expression, variables);
            StandardEvaluationContext context = new StandardEvaluationContext(data);
            context.addPropertyAccessor(new MapAccessor());
            Expression parsed = parser.parseExpression(expression);
            return parsed.getValue(context);
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override
    public boolean evaluateLogic(String expression, Map<String, Object> variables) {
        try {
            Object result = evaluate(expression, variables);
            if (result == null) {
                return false;
            }
            if (result instanceof Boolean b) {
                return b;
            }
            return Boolean.parseBoolean(String.valueOf(result));
        } catch (Exception ignored) {
            return false;
        }
    }

    private Map<String, Object> buildContext(String expression, Map<String, Object> variables) {
        Map<String, Object> data = new HashMap<>();
        // Seed $vars referenced in the expression as null (old getVarsNamesFromExprAsHashMapNull).
        Matcher m = DOLLAR_VARS.matcher(expression);
        while (m.find()) {
            String bare = m.group(2);
            data.putIfAbsent(bare, null);
            data.putIfAbsent("$" + bare, null);
        }
        if (variables != null) {
            data.putAll(variables);
            for (Map.Entry<String, Object> e : variables.entrySet()) {
                String k = e.getKey();
                if (k != null && !k.startsWith("$")) {
                    data.put("$" + k, e.getValue());
                }
            }
        }
        for (ScriptNamespaceProvider provider : namespaceProviders) {
            if (ExprStrHeuristic.isUseClass(expression, provider.namespace())) {
                data.put(provider.namespace(), provider.helper());
            }
        }
        return data;
    }
}
