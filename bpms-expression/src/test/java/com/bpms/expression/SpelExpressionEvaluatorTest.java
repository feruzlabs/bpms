package com.bpms.expression;

import com.bpms.spi.script.ScriptNamespaceProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpelExpressionEvaluatorTest {

    @Test
    void returnsLiteralWhenNotExprStr() {
        SpelExpressionEvaluator eval = new SpelExpressionEvaluator();
        // No + - * / . , $ ' "  → literal, even if it looks like a comparison
        assertEquals("APPROVED", eval.evaluate("APPROVED", Map.of()));
        assertEquals("score > 10", eval.evaluate("score > 10", Map.of("score", 20)));
    }

    @Test
    void evaluatesSpelWhenHeuristicMatches() {
        SpelExpressionEvaluator eval = new SpelExpressionEvaluator();
        // '.' triggers isExprStr (old SystemHelper heuristic)
        Object result = eval.evaluate("score.intValue() > 10", Map.of("score", 20));
        assertEquals(Boolean.TRUE, result);
        assertTrue(eval.evaluateLogic("score.intValue() > 10", Map.of("score", 20)));
        assertFalse(eval.evaluateLogic("score.intValue() > 10", Map.of("score", 5)));
        // quotes trigger heuristic
        assertTrue(eval.evaluateLogic("status == 'OK'", Map.of("status", "OK")));
    }

    @Test
    void brokenExpressionYieldsNullThenFalse() {
        SpelExpressionEvaluator eval = new SpelExpressionEvaluator();
        assertNull(eval.evaluate("unknownVar.foo", Map.of()));
        assertFalse(eval.evaluateLogic("unknownVar.foo", Map.of()));
    }

    @Test
    void injectsDomainScriptNamespaceViaSpi() {
        ScriptNamespaceProvider provider = new ScriptNamespaceProvider() {
            @Override
            public String namespace() {
                return "credit";
            }

            @Override
            public Object helper() {
                return new CreditHelper();
            }
        };
        SpelExpressionEvaluator eval = new SpelExpressionEvaluator(List.of(provider));
        assertEquals("ok", eval.evaluate("credit.ping()", Map.of()));
    }

    @Test
    void dollarPrefixedVarEqualsNullWhenMissing() {
        SpelExpressionEvaluator eval = new SpelExpressionEvaluator();
        assertTrue(eval.evaluateLogic("$employee_sign == null", Map.of()));
        assertFalse(eval.evaluateLogic("$employee_sign == null", Map.of("employee_sign", "ok")));
    }

    public static final class CreditHelper {
        public String ping() {
            return "ok";
        }
    }
}