package com.bpms.connectors.creditconveyer.dto;

import lombok.Data;

import java.util.Map;


@Data
public class GetClientInfoResponseDTO {
     String token;
     ClientData data;

     @Data
    public static class ClientData{
         Client client;
    }

    @Data
    public static class Client{
         int id;
         String name;
         Map data;
    }
}
