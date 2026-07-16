package com.bpms.connectors.creditconveyer.dto.v6;


import lombok.Data;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
@Getter
public class ScoringLogV6DTO {
    private String token;
    private DataContainer data;

    @Data
    @Getter
    public static class DataContainer {
        private Client client;
    }

    @Data
    @Getter
    public static class Client {
        private int id;
        private String name;
        private ClientData data;
    }

    @Data
    public static class ClientData {
        private Request request;
        private Response response;
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
        private Logs logs;
        private List<Loan> loans;
        private String status;
        private long clientMonthlyIncome;
    }

    @Data
    public static class Logs {
        private Map<String, Product> products;
        private ClientInfo clientData;
        private List<StopFactor> stopFactors;
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
        private KATMInfo katm;
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
}

