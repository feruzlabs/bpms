package com.bpms.spi.expression;

import java.util.Map;

public interface ExpressionEvaluator {
    Object evaluate(String expression, Map<String, Object> variables);

    boolean evaluateLogic(String expression, Map<String, Object> variables);
}