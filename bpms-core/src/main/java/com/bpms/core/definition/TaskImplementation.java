package com.bpms.core.definition;

public sealed interface TaskImplementation permits
        ConnectorImplementation,
        DelegateExpressionImplementation,
        ExternalTopicImplementation,
        ClassImplementation,
        ExpressionImplementation,
        EmptyImplementation {
}