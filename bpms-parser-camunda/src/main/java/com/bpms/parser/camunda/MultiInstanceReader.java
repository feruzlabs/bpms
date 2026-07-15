package com.bpms.parser.camunda;

import com.bpms.core.definition.MultiInstanceSpec;
import org.camunda.bpm.model.bpmn.impl.instance.MultiInstanceLoopCharacteristicsImpl;
import org.camunda.bpm.model.bpmn.instance.Activity;
import org.camunda.bpm.model.bpmn.instance.LoopCharacteristics;
import org.camunda.bpm.model.bpmn.instance.MultiInstanceLoopCharacteristics;

import java.util.Optional;

/** Mirrors {@code BpmHelper.getMultiInstance}. */
final class MultiInstanceReader {

    private MultiInstanceReader() {
    }

    static Optional<MultiInstanceSpec> read(Activity activity) {
        try {
            LoopCharacteristics loop = activity.getLoopCharacteristics();
            if (!(loop instanceof MultiInstanceLoopCharacteristics mi)) {
                return Optional.empty();
            }
            MultiInstanceLoopCharacteristicsImpl impl = (MultiInstanceLoopCharacteristicsImpl) mi;
            String cardinality = impl.getLoopCardinality() != null ? impl.getLoopCardinality().getTextContent() : null;
            String completion = impl.getCompletionCondition() != null ? impl.getCompletionCondition().getTextContent() : null;
            return Optional.of(new MultiInstanceSpec(
                    impl.isSequential(),
                    cardinality,
                    impl.getCamundaCollection(),
                    impl.getCamundaElementVariable(),
                    completion
            ));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }
}