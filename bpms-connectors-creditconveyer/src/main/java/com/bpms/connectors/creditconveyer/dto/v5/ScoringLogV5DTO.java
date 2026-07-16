package com.bpms.connectors.creditconveyer.dto.v5;


import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
public class ScoringLogV5DTO {
    private String token;
    private DataContainer data;
}

@Data
class DataContainer {
    private Client client;
}

@Data
class Client {
    private int id;
    private String name;
    private ClientData data;
}

@Data
class ClientData {
    private Request request;
    private Response response;
}

@Data
class Request {
    private String pin;
    private String passport;
    private String requestId;
    private String productCodeAbs;
}

@Data
class Response {
    private Logs logs;
    private List<Loan> loans;
    private String status;
    private long clientMonthlyIncome;
}

@Data
class Logs {
    private Map<String, Product> products;
    private ClientInfo clientData;
    private List<StopFactor> stopFactors;
}

@Data
class Product {
    private Object score;
    private String status;
    private String message;
    private int lifeTime;
    private BigDecimal percentage;
    private int gracePeriod;
}


@Data
class Formula {
    private String param;
    private double result;
    private String formula;
}

@Data
class CreditSumLog {
    private List<String> purpose;
}

@Data
class ClientInfo {
    private boolean isPrivilegedClient;
}

@Data
class StopFactor {
    private int id;
    private String uid;
    private String type;
    private boolean state;
    private Map<String, String> params;
    private String message;
    private Map<String, String> components;
}

@Data
class Loan {
    private int month;
    private long amount;
    private String status;
    private String message;
    private BigDecimal percentage;
}
