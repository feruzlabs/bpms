package com.bpms.core.definition;

import java.util.List;

/** Embedded Camunda start/user-task form (BPMN extension, no separate DB table). */
public record FormDataSpec(
        String formKey,
        /** Field id whose value becomes {@code process_instance.business_key} (formData {@code businessKey} attr or TUNE heuristic). */
        String businessKeyVar,
        List<FormFieldSpec> fields
) {
    /** Backward-compatible: formKey/businessKeyVar unknown. */
    public FormDataSpec(List<FormFieldSpec> fields) {
        this(null, null, fields);
    }
}