package com.bpms.connectors.creditconveyer.dto;

import lombok.Data;

@Data
public class RefreshServiceErrorResponseDTO {
     String name;
     String message;
     int code;
     int status;
     String type;
}
