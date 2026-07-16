package com.bpms.connectors.creditconveyer.dto;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;
import java.util.List;

@Data
public class CallBackTuneSendV5DTO {
    public int id;
    public String jsonrpc;
    public String method;
    public Params params;

    @lombok.Data
    public static class Params {
        public String passport;
        public String pinfl;
        public String requestId;
        public String productId;
        public String status;
        public Data data;
    }

    @lombok.Data
    public static class Data implements Serializable {
        public List<AvailableLoanDTO> loans;
        @JsonProperty("client_monthly_income")
        public Long clientMonthlyIncome = 0L;
        public String message;
    }

    @lombok.Data
    public static class AvailableLoanDTO implements Serializable {
        float percentage = 0;
        String amount = "0";
        int month = 0;
        String status;
        String message;
    }
}
