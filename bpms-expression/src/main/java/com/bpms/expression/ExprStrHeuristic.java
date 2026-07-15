package com.bpms.expression;

import java.util.regex.Pattern;

/**
 * Exact replica of old {@code SystemHelper.isExprStr}: a string is treated as a SpEL
 * expression only if it contains one of {@code + - * / . , $ ' "}.
 */
public final class ExprStrHeuristic {

    private static final Pattern EXPR_CHARS = Pattern.compile("[\\+\\-\\*\\/\\.\\,\\$\\'\\\"]+", Pattern.MULTILINE);

    private ExprStrHeuristic() {
    }

    public static boolean isExprStr(String string) {
        if (string == null) {
            return false;
        }
        string = string.trim();
        return EXPR_CHARS.matcher(string).find();
    }

    public static boolean isUseClass(String string, String className) {
        if (string == null || className == null) {
            return false;
        }
        string = string.trim();
        Pattern pattern = Pattern.compile(Pattern.quote(className) + "\\.", Pattern.MULTILINE);
        return pattern.matcher(string).find();
    }

    public static boolean isUseVar(String string, String varName) {
        if (string == null || varName == null) {
            return false;
        }
        string = string.trim();
        Pattern pattern = Pattern.compile(Pattern.quote(varName), Pattern.MULTILINE);
        return pattern.matcher(string).find();
    }
}