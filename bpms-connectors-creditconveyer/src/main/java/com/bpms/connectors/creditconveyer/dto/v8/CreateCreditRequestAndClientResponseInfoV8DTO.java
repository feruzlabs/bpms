package com.bpms.connectors.creditconveyer.dto.v8;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class CreateCreditRequestAndClientResponseInfoV8DTO implements Serializable {
    List<AvailableLoanDTO> loans;
    float client_monthly_income;
    String message;
    String status;

    @Data
    public static class AvailableLoanDTO implements Serializable {
        float percentage = 0;
        String amount = "0";
        int month = 0;
        String status;
        String message;
    }

    public float getClientMonthlyIncome() {
        return this.getClient_monthly_income();
    }
}
