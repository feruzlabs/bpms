package com.bpms.connectors.creditconveyer.service;

import com.bpms.connectors.creditconveyer.dto.LogCreditRequestCreateDTO;
import com.google.gson.Gson;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Optional BI state logger. Never throws — blank {@code bi.endpoint} skips the call.
 */
@Service
public class BiSetStateService {

    private static final MediaType JSON = MediaType.parse("application/json");

    private final String endpoint;
    private final OkHttpClient http;
    private final Gson gson;

    public BiSetStateService(
            @Value("${bi.endpoint:}") String endpoint,
            OkHttpClient creditConveyerHttpClient,
            Gson creditConveyerGson
    ) {
        this.endpoint = endpoint == null ? "" : endpoint.trim();
        this.http = creditConveyerHttpClient;
        this.gson = creditConveyerGson;
    }

    public boolean isConfigured() {
        return !endpoint.isBlank();
    }

    /** Best-effort POST to {@code /v1/log-credit-request}. Swallows all failures. */
    public void setState(LogCreditRequestCreateDTO dto) {
        if (!isConfigured() || dto == null) {
            return;
        }
        try {
            RequestBody body = RequestBody.create(gson.toJson(dto), JSON);
            Request request = new Request.Builder()
                    .url(endpoint + "/v1/log-credit-request")
                    .post(body)
                    .addHeader("accept", "*/*")
                    .addHeader("Content-Type", "application/json")
                    .build();
            try (Response response = http.newCall(request).execute()) {
                // ignore status — soft no-op
            }
        } catch (Exception ignored) {
            // swallow — never fail the token
        }
    }
}
