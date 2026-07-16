package com.bpms.connectors.creditconveyer.dto;

import lombok.Data;

@Data
public class CallBackTuneSendDTO {
    public int id;
    public String jsonrpc;
    public String method;
    public Params params;

    @lombok.Data
    public static class Params{
        public String passport;
        public String pinfl;
        public String requestId;
        public String productId;
        public String status;
        public Data data;
    }

    @lombok.Data
    public static class Data{
        public long amount;
    }
}
