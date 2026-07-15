package com.bpms.spi.engine;

import java.util.Map;
import com.bpms.spi.engine.RuntimeModels.DeployResult;
import com.bpms.spi.engine.RuntimeModels.InstanceView;

public interface ProcessEnginePort {
    DeployResult deploy(byte[] bpmnXml);
    InstanceView start(String definitionKeyOrId, String businessKey, Map<String, Object> variables);
    InstanceView getInstance(String instanceId);
    InstanceView completeTask(String taskId, Map<String, Object> variables);
}
