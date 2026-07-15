package com.bpms.parser.camunda;

import com.bpms.core.definition.TimerEventDef;
import com.bpms.core.definition.TimerKind;
import org.camunda.bpm.model.bpmn.instance.TimerEventDefinition;

/** Mirrors {@code BpmHelper.getTimerType/getTimerValue}. */
final class TimerReader {

    private TimerReader() {
    }

    static TimerEventDef read(TimerEventDefinition def) {
        if (def.getTimeDate() != null) {
            return new TimerEventDef(TimerKind.DATE, def.getTimeDate().getTextContent());
        }
        if (def.getTimeDuration() != null) {
            return new TimerEventDef(TimerKind.DURATION, def.getTimeDuration().getTextContent());
        }
        if (def.getTimeCycle() != null) {
            return new TimerEventDef(TimerKind.CYCLE, def.getTimeCycle().getTextContent());
        }
        return new TimerEventDef(TimerKind.DURATION, null);
    }
}