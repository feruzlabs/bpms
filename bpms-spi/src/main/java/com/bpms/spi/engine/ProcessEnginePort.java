package com.bpms.spi.engine;

import java.util.Map;

public interface ProcessEnginePort {
    RuntimeModels.DeployResult deploy(byte[] bpmnXml);

    RuntimeModels.InstanceView start(String definitionKeyOrId, String businessKey, Map<String, Object> variables);

    default RuntimeModels.InstanceView start(
            String definitionKeyOrId, String businessKey, Map<String, Object> variables, String startedBy
    ) {
        return start(definitionKeyOrId, businessKey, variables);
    }

    RuntimeModels.InstanceView getInstance(String instanceId);

    RuntimeModels.InstanceView completeTask(String taskId, Map<String, Object> variables);

    default RuntimeModels.InstanceView claimTask(String taskId, String assignee) {
        throw new UnsupportedOperationException("claimTask");
    }
}
