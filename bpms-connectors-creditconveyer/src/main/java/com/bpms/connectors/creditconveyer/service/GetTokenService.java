package com.bpms.connectors.creditconveyer.service;

import com.bpms.connectors.creditconveyer.dto.AuthLoginResponseDTO;
import com.bpms.connectors.creditconveyer.dto.AuthRequestDTO;
import com.bpms.connectors.creditconveyer.dto.TokenInfo;
import com.bpms.connectors.creditconveyer.exceptions.ServerErrorGetTokenAuthException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;

@Service
public class GetTokenService {

    private static final String PATH = "/v1/auth/default/login-app";
    private static final String CONTENT_TYPE_JSON = "application/json;charset=UTF-8";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse(CONTENT_TYPE_JSON);
    private static final long DEFAULT_TOKEN_VALIDITY_MINUTES = 60;
    private static final long DEFAULT_TOKEN_VALIDITY_EXP_SEC = 300;

    private final ObjectMapper objectMapper;
    private final Gson gson;
    private final OkHttpClient httpClient;
    private final String endpoint;
    private final String login;
    private final String password;

    private volatile String cachedToken;
    private volatile Instant tokenExpiryTime;
    private String loginPath = PATH;

    public GetTokenService(
            ObjectMapper objectMapper,
            Gson gson,
            OkHttpClient creditConveyerHttpClient,
            @Value("${creditconveyer.endpoint}") String endpoint,
            @Value("${creditconveyer.login:bpms}") String login,
            @Value("${creditconveyer.passport:bpms}") String password
    ) {
        this.objectMapper = objectMapper;
        this.gson = gson;
        this.httpClient = creditConveyerHttpClient;
        this.endpoint = endpoint;
        this.login = login;
        this.password = password;
    }

    public synchronized String getToken() throws ServerErrorGetTokenAuthException {
        if (isTokenValid()) {
            return cachedToken;
        }
        return requestNewToken();
    }

    public synchronized String refreshToken() throws ServerErrorGetTokenAuthException {
        clearToken();
        return requestNewToken();
    }

    public synchronized void clearToken() {
        this.cachedToken = null;
        this.tokenExpiryTime = null;
    }

    private boolean isTokenValid() {
        if (cachedToken == null || tokenExpiryTime == null) {
            return false;
        }
        Instant bufferTime = Instant.now().plusSeconds(DEFAULT_TOKEN_VALIDITY_EXP_SEC);
        return tokenExpiryTime.isAfter(bufferTime);
    }

    private String requestNewToken() throws ServerErrorGetTokenAuthException {
        try {
            AuthRequestDTO loginRequest = new AuthRequestDTO();
            loginRequest.setPhone(login);
            loginRequest.setPassword(password);
            String requestBody = objectMapper.writeValueAsString(loginRequest);

            Request request = new Request.Builder()
                    .url(endpoint + getLoginPath())
                    .post(RequestBody.create(requestBody, JSON_MEDIA_TYPE))
                    .addHeader("Content-Type", CONTENT_TYPE_JSON)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                return processTokenResponse(response);
            }
        } catch (IOException e) {
            throw new ServerErrorGetTokenAuthException("Failed to request token due to network error: " + e.getMessage());
        } catch (ServerErrorGetTokenAuthException e) {
            throw e;
        } catch (Exception e) {
            throw new ServerErrorGetTokenAuthException("Unexpected error during token request: " + e.getMessage());
        }
    }

    private String processTokenResponse(Response response) throws ServerErrorGetTokenAuthException, IOException {
        ResponseBody responseBody = response.body();
        if (responseBody == null) {
            throw new ServerErrorGetTokenAuthException("Empty response from auth service");
        }

        String responseData = responseBody.string();

        if (!response.isSuccessful()) {
            if (response.code() == 401) {
                throw new ServerErrorGetTokenAuthException("Invalid credentials for token request");
            } else if (response.code() == 404) {
                throw new ServerErrorGetTokenAuthException("Auth service endpoint not found");
            } else if (response.code() >= 500) {
                throw new ServerErrorGetTokenAuthException("Auth service internal error");
            } else {
                throw new ServerErrorGetTokenAuthException("Token request failed with status: " + response.code());
            }
        }

        try {
            AuthLoginResponseDTO tokenResponse = gson.fromJson(responseData, AuthLoginResponseDTO.class);
            if (tokenResponse == null || !tokenResponse.isValid()) {
                throw new ServerErrorGetTokenAuthException("Invalid token response format or inactive token");
            }
            this.cachedToken = tokenResponse.getToken();
            this.tokenExpiryTime = calculateTokenExpiry(tokenResponse);
            return cachedToken;
        } catch (JsonSyntaxException e) {
            throw new ServerErrorGetTokenAuthException("Failed to parse token response");
        }
    }

    private Instant calculateTokenExpiry(AuthLoginResponseDTO tokenResponse) {
        if (tokenResponse.getExpire_at() != null) {
            return Instant.ofEpochSecond(tokenResponse.getExpire_at());
        }
        return Instant.now().plusSeconds(DEFAULT_TOKEN_VALIDITY_MINUTES * 60);
    }

    public TokenInfo getTokenInfo() {
        return new TokenInfo(cachedToken != null, tokenExpiryTime, isTokenValid());
    }

    public String getLoginPath() {
        if (loginPath == null) {
            loginPath = PATH;
        }
        return loginPath;
    }

    public void setLoginPath(String loginPath) {
        this.loginPath = loginPath;
    }
}
