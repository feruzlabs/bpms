package com.bpms.expression;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TemplateExpressionsTest {

    @Test
    void interpolatesCamundaTemplates() {
        assertEquals(
                "https://open.er-api.com/v6/latest/USD",
                TemplateExpressions.interpolate("https://open.er-api.com/v6/latest/${base}", Map.of("base", "USD")));
        assertEquals("rates.UZS", TemplateExpressions.interpolate("rates.${target}", Map.of("target", "UZS")));
        assertEquals("12000", TemplateExpressions.interpolate("${threshold}", Map.of("threshold", 12000)));
    }

    @Test
    void resolveDelegatesToSpelWhenNoTemplate() {
        SpelExpressionEvaluator eval = new SpelExpressionEvaluator();
        assertEquals(true, TemplateExpressions.resolve("$aboveThreshold == true", eval, Map.of("aboveThreshold", true)));
        assertEquals(
                "https://x/USD",
                TemplateExpressions.resolve("https://x/${base}", eval, Map.of("base", "USD")));
    }
}
