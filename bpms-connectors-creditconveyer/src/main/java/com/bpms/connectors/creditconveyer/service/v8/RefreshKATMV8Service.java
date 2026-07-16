package com.bpms.connectors.creditconveyer.service.v8;

import com.bpms.connectors.creditconveyer.dto.ErrorResponseDTO;
import com.bpms.connectors.creditconveyer.exceptions.APIErrorException;
import com.bpms.connectors.creditconveyer.exceptions.APIGoneException;
import com.bpms.connectors.creditconveyer.exceptions.APINotFoundException;
import com.bpms.connectors.creditconveyer.exceptions.APIUnauthorizedException;
import com.bpms.connectors.creditconveyer.exceptions.APIUnprocessableEntityException;
import com.bpms.connectors.creditconveyer.exceptions.ServerErrorGetTokenAuthException;
import com.bpms.connectors.creditconveyer.service.GetTokenService;
import com.google.gson.Gson;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
public class RefreshKATMV8Service<R, E extends ErrorResponseDTO> {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String CONTENT_TYPE_JSON = "application/json";
    private static final int HTTP_UNAUTHORIZED = 401;
    private static final int HTTP_NOT_FOUND = 404;
    private static final int HTTP_GONE = 410;
    private static final int HTTP_UNPROCESSABLE = 422;

    private final Gson gson;
    private final GetTokenService getTokenService;
    private final OkHttpClient httpClient;

    public RefreshKATMV8Service(Gson gson, GetTokenService getTokenService, OkHttpClient creditConveyerHttpClient) {
        this.gson = gson;
        this.getTokenService = getTokenService;
        this.httpClient = creditConveyerHttpClient;
    }

    public R refresh(String endpoint, String path, String token, Class<R> responseType, Class<E> errorClassType)
            throws IOException,
            ServerErrorGetTokenAuthException,
            APINotFoundException,
            APIErrorException,
            APIUnprocessableEntityException,
            APIUnauthorizedException,
            APIGoneException {
        try (Response response = getResponse(endpoint + path + token)) {
            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                throw new APIErrorException("Empty response received");
            }
            String responseData = responseBody.string();
            if (!response.isSuccessful()) {
                E serviceErrorResponseDTO = gson.fromJson(responseData, errorClassType);
                String message = serviceErrorResponseDTO != null ? serviceErrorResponseDTO.getMessage() : "Unknown error";
                if (response.code() == HTTP_NOT_FOUND) {
                    throw new APINotFoundException(message);
                }
                if (response.code() == HTTP_UNAUTHORIZED) {
                    getTokenService.refreshToken();
                    throw new APIUnauthorizedException(message);
                }
                if (response.code() == HTTP_UNPROCESSABLE) {
                    throw new APIUnprocessableEntityException(message);
                }
                if (response.code() == HTTP_GONE) {
                    throw new APIGoneException(message);
                }
                throw new APIErrorException(message);
            }
            return gson.fromJson(responseData, responseType);
        }
    }

    protected Response getResponse(String path) throws ServerErrorGetTokenAuthException, IOException {
        Request request = new Request.Builder()
                .url(path)
                .get()
                .addHeader("Accept", CONTENT_TYPE_JSON)
                .addHeader("Authorization", BEARER_PREFIX + getTokenService.getToken())
                .build();
        return httpClient.newCall(request).execute();
    }
}
