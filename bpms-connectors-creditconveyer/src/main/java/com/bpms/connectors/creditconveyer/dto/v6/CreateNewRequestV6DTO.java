package com.bpms.connectors.creditconveyer.dto.v6;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateNewRequestV6DTO {
    private String pin;
    private String passport;
    private String requestId;
    private String productCodeAbs;
    private int term;
    private int grace = 0;
    private Long amount;
    private double percent;
}
