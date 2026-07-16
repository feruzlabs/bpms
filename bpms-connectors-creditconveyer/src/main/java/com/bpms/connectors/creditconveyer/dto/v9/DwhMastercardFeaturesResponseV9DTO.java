package com.bpms.connectors.creditconveyer.dto.v9;

import lombok.Data;

import java.util.Map;

@Data
public class DwhMastercardFeaturesResponseV9DTO {

    private String status;
    private String message;
    private Map<String, Object> data;
}
