package com.bpms.connectors.creditconveyer.dto;

import lombok.Data;

import java.time.Instant;

@Data
public class AuthLoginResponseDTO {
    Long expire_at;
    String token;
    Long user_id;
    Long type;
    Long status;
    Long request_data;
    Long id;

    public Instant getExpiryTime() {
        if (expire_at != null) {
            return Instant.ofEpochSecond(expire_at);
        }
        return null;
    }

    // Helper method to check if response is valid
    public boolean isValid() {
        return token != null && !token.trim().isEmpty() &&
                expire_at != null && expire_at > 0 &&
                status != null && status == 1; // assuming status 1 means success
    }
}
