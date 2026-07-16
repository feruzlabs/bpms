package com.bpms.connectors.creditconveyer.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;

@Data
@AllArgsConstructor
public class TokenInfo {
    private boolean tokenExists;
    private Instant expiryTime;
    private boolean isValid;
}
