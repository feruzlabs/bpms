package com.bpms.connectors.creditconveyer.dto.v7;

import com.bpms.connectors.creditconveyer.dto.v6.ScoringLogV6DTO;
import lombok.Data;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
@Getter
public class ScoringLogV7DTO {
    private String token;
    private ScoringLogV7DTO.DataContainer data;

    @Data
    @Getter
    public static class DataContainer {
        private ScoringLogV7DTO.Client client;
    }

    @Data
    @Getter
    public static class Client {
        private int id;
        private String name;
        private ScoringLogV7DTO.ClientData data;
    }

    @Data
    public static class ClientData {
        private ScoringLogV7DTO.Request request;
        private ScoringLogV7DTO.Response response;
    }

    @Data
    public static class Request {
        private String pin;
        private String passport;
        private String requestId;
        private String productCodeAbs;
    }

    @Data
    public static class Response {
        private ScoringLogV7DTO.Logs logs;
        private List<ScoringLogV7DTO.Loan> loans;
        private String status;
        private long clientMonthlyIncome;
        private List<ClientScore> clientScore;
    }

    @Data
    public static class Logs {
        private Map<String, ScoringLogV7DTO.Product> products;
        private ScoringLogV7DTO.ClientInfo clientData;
        private List<ScoringLogV7DTO.StopFactor> stopFactors;
    }

    @Data
    public static class Product {
        private Object score;
        private String status;
        private String message;
        private int lifeTime;
        private BigDecimal percentage;
        private int gracePeriod;
    }


    @Data
    public static class Formula {
        private String param;
        private double result;
        private String formula;
    }

    @Data
    public static class CreditSumLog {
        private List<String> purpose;
    }

    @Data
    public static class ClientInfo {
        private ScoringLogV7DTO.KATMInfo katm;
        private boolean isPrivilegedClient;
    }

    @Data
    public static class KATMInfo {
        private String claimId;
    }

    @Data
    public static class StopFactor {
        public int id;
        public String uid;
        public String type;
        public boolean state;
        public Map<String, String> params;
        public String message;
        public Map<String, String> components;
    }

    @Data
    public static class Loan {
        private int month;
        private long amount;
        private String status;
        private String message;
        private BigDecimal percentage;
    }

    @Data
    public static class ClientScore {
        private Long id;
        private String tag;
        private String name;
        private String version;
        private Long sum_score;
    }
}

