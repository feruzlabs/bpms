package com.bpms.connectors.creditconveyer.dto.v6;

import lombok.Data;

import java.util.Map;

@Data
public class RefreshActiveAccountResponseDTO {
    Map body;
    Map accounts;
}
