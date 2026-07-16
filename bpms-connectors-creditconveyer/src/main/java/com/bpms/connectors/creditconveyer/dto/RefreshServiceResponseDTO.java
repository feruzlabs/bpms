package com.bpms.connectors.creditconveyer.dto;

import lombok.Data;

import java.util.Map;

@Data
public class RefreshServiceResponseDTO {
    String token;
    Map data;
}
