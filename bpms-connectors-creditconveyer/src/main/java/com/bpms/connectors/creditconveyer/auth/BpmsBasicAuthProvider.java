package com.bpms.connectors.creditconveyer.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Component("basicAuth")
public class BpmsBasicAuthProvider implements ConveyorAuthProvider {

    private static final String BASIC_PREFIX = "Basic ";

    private final String authorizationHeader;

    public BpmsBasicAuthProvider(
            @Value("${creditconveyer.bpms.user:bpms}") String user,
            @Value("${creditconveyer.bpms.pass:bpms}") String pass) {
        String credentials = user + ":" + pass;
        this.authorizationHeader = BASIC_PREFIX
                + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String authorizationHeader() {
        return authorizationHeader;
    }
}
