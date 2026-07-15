package com.bpms.expression;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExprStrHeuristicTest {

    @Test
    void literalsWithoutSpecialCharsAreNotExpressions() {
        assertFalse(ExprStrHeuristic.isExprStr("APPROVED"));
        assertFalse(ExprStrHeuristic.isExprStr("true"));
        assertFalse(ExprStrHeuristic.isExprStr(null));
    }

    @Test
    void stringsWithSpecialCharsAreExpressions() {
        assertTrue(ExprStrHeuristic.isExprStr("a.b"));
        assertTrue(ExprStrHeuristic.isExprStr("x + 1"));
        assertTrue(ExprStrHeuristic.isExprStr("$var"));
        assertTrue(ExprStrHeuristic.isExprStr("status == 'OK'"));
        assertTrue(ExprStrHeuristic.isExprStr("a,b"));
    }
}