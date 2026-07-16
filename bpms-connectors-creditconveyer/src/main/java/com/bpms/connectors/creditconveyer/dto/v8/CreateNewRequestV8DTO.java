package com.bpms.connectors.creditconveyer.dto.v8;

import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateNewRequestV8DTO {
    private String pin;
    private String passport;
    private String requestId;
    private String productCodeAbs;
    private boolean pensioner = false;
    private int term;
    private int grace = 0;
    private Long amount;
    private double percent;
}
