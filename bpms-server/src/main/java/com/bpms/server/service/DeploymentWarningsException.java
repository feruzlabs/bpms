package com.bpms.server.service;
import com.bpms.core.compat.CompatWarning;
import java.util.List;
public class DeploymentWarningsException extends RuntimeException {
    private final List<CompatWarning> warnings;
    public DeploymentWarningsException(List<CompatWarning> warnings) { super("BPMN contains compatibility warnings"); this.warnings=warnings; }
    public List<CompatWarning> warnings() { return warnings; }
}
