package com.bpms.core.definition;

public record ExpressionImplementation(String expression, String resultVariable) implements TaskImplementation {
}