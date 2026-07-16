package com.bpms.connectors.creditconveyer.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;


@Data
public class GetClientInfoV2ResponseDTO {
    String token;
    ClientData data;

    @Data
    public static class ClientData {
        ClientInfo client;
    }

    @Data
    public static class ClientInfo {
        int id;
        String name;
        InfoData data;
    }

    @Data
    public static class InfoData {
        Map request;
        ResponseInfoData response;
    }

    @Data
    public static class ResponseInfoData {
        String status;
        String message;
        Long creditSum;
        ScoringInfoData scoringLog;
    }

    @Data
    public static class ScoringInfoData {
        Map product;
        ClientInfoData clientData;
        List<Map> stopFactors;
    }

    @Data
    public static class ClientInfoData {
        Long avg_income;
        Boolean isPrivilegedClient;
    }
}
