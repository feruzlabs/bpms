package com.bpms.connectors.creditconveyer.dto;

import lombok.Data;

@Data
public class LogCreditRequestCreateDTO {
    private String requestIdAlt;
    private String pinId;
    private String passport;
    private String state;
    private Object data;
    private String serviceType = "CREDIT";
}
