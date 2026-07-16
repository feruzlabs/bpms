package com.bpms.connectors.creditconveyer.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

@Data
public class CallBackTuneSendV6DTO {
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
        public List<StopFactor> stopFactors;
    }

    @lombok.Data
    public static class Data implements Serializable {
        public List<AvailableLoanDTO> loans;
        @JsonProperty("client_monthly_income")
        public Long clientMonthlyIncome = 0L;
        public String message;
        public KATMData katm;
    }

    @lombok.Data
    public static class KATMData implements Serializable {
        public String claimId;
    }

    @lombok.Data
    @Builder
    public static class StopFactor implements Serializable {
        public int id;
        public String uid;
        public boolean result;
        public Map<String, String> params;
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
