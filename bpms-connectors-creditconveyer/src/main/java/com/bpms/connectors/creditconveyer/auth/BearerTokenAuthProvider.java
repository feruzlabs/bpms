package com.bpms.connectors.creditconveyer.auth;

import com.bpms.connectors.creditconveyer.exceptions.ServerErrorGetTokenAuthException;
import com.bpms.connectors.creditconveyer.service.GetTokenService;
import org.springframework.stereotype.Component;

@Component("bearerAuth")
public class BearerTokenAuthProvider implements ConveyorAuthProvider {

    private static final String BEARER_PREFIX = "Bearer ";

    private final GetTokenService getTokenService;

    public BearerTokenAuthProvider(GetTokenService getTokenService) {
        this.getTokenService = getTokenService;
    }

    @Override
    public String authorizationHeader() {
        try {
            return BEARER_PREFIX + getTokenService.getToken();
        } catch (ServerErrorGetTokenAuthException e) {
            throw new IllegalStateException("Failed to obtain bearer token: " + e.getMessage(), e);
        }
    }
}
