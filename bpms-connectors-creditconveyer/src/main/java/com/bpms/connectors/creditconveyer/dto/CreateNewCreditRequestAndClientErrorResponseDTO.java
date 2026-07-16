package com.bpms.connectors.creditconveyer.dto;

import lombok.Data;

@Data
public class CreateNewCreditRequestAndClientErrorResponseDTO {
    private String name;
    private String message;
    private int code;
    private int status;
    private String type;
}
