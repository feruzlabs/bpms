package com.bpms.connectors.creditconveyer.service;

import com.bpms.connectors.creditconveyer.dto.ErrorResponseDTO;
import com.bpms.connectors.creditconveyer.exceptions.APIBadRequestException;
import com.bpms.connectors.creditconveyer.exceptions.APIErrorException;
import com.bpms.connectors.creditconveyer.exceptions.APINotFoundException;
import com.bpms.connectors.creditconveyer.exceptions.APIUnauthorizedException;
import com.bpms.connectors.creditconveyer.exceptions.APIUnprocessableEntityException;
import com.bpms.connectors.creditconveyer.exceptions.ApiInternalErrorException;
import com.bpms.connectors.creditconveyer.exceptions.ServerErrorGetTokenAuthException;
import com.bpms.connectors.creditconveyer.exceptions.ValidationErrorException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
public class CreateRequestService<T, R, E extends ErrorResponseDTO> {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String CONTENT_TYPE_JSON = "application/json";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse(CONTENT_TYPE_JSON);
    private static final int HTTP_BAD_REQUEST = 400;
    private static final int HTTP_UNAUTHORIZED = 401;
    private static final int HTTP_NOT_FOUND = 404;
    private static final int HTTP_UNPROCESSABLE = 422;
    private static final int HTTP_INTERNAL = 500;

    private final Gson gson;
    private final ObjectMapper objectMapper;
    private final GetTokenService getTokenService;
    private final OkHttpClient httpClient;

    public CreateRequestService(
            Gson gson,
            ObjectMapper objectMapper,
            GetTokenService getTokenService,
            OkHttpClient creditConveyerHttpClient
    ) {
        this.gson = gson;
        this.objectMapper = objectMapper;
        this.getTokenService = getTokenService;
        this.httpClient = creditConveyerHttpClient;
    }

    public R create(String endpoint, String path, T requestData, Class<R> responseType, Class<E> errorClassType)
            throws IOException,
            ServerErrorGetTokenAuthException,
            APIUnprocessableEntityException,
            APIErrorException,
            ApiInternalErrorException,
            ValidationErrorException,
            APIBadRequestException,
            APIUnauthorizedException,
            APINotFoundException {
        String requestBody = objectMapper.writeValueAsString(requestData);
        Request request = buildRequest(endpoint + path, requestBody);

        try (Response response = httpClient.newCall(request).execute()) {
            return processResponse(response, responseType, errorClassType);
        }
    }

    private R processResponse(Response response, Class<R> responseType, Class<E> errorClassType)
            throws IOException,
            ValidationErrorException,
            APIUnprocessableEntityException,
            APIErrorException,
            ApiInternalErrorException,
            APIBadRequestException,
            APIUnauthorizedException,
            APINotFoundException {
        ResponseBody responseBody = response.body();
        if (responseBody == null) {
            throw new APIErrorException("Empty response received");
        }

        String responseData = responseBody.string();
        if (response.isSuccessful()) {
            return gson.fromJson(responseData, responseType);
        }

        handleErrorResponse(response.code(), responseData, errorClassType);
        return null;
    }

    private Request buildRequest(String url, String requestBody) throws ServerErrorGetTokenAuthException {
        return new Request.Builder()
                .url(url)
                .post(RequestBody.create(requestBody, JSON_MEDIA_TYPE))
                .addHeader("Authorization", BEARER_PREFIX + getToken())
                .addHeader("Content-Type", CONTENT_TYPE_JSON)
                .build();
    }

    private void handleErrorResponse(int statusCode, String responseData, Class<E> errorClassType)
            throws ValidationErrorException,
            APIBadRequestException,
            APIUnauthorizedException,
            APINotFoundException,
            APIUnprocessableEntityException,
            ApiInternalErrorException,
            APIErrorException {
        try {
            E errorResponse = gson.fromJson(responseData, errorClassType);
            String errorMessage = errorResponse != null ? errorResponse.getMessage() : "Unknown error";

            if (statusCode == HTTP_BAD_REQUEST) {
                throw new APIBadRequestException(errorMessage);
            } else if (statusCode == HTTP_UNAUTHORIZED) {
                getTokenService.refreshToken();
                throw new APIUnauthorizedException(errorMessage);
            } else if (statusCode == HTTP_NOT_FOUND) {
                throw new APINotFoundException(errorMessage);
            } else if (statusCode == HTTP_UNPROCESSABLE) {
                if (isValidationError(responseData)) {
                    throw new ValidationErrorException(errorMessage);
                }
                throw new APIUnprocessableEntityException(errorMessage);
            } else if (statusCode == HTTP_INTERNAL) {
                throw new ApiInternalErrorException(errorMessage);
            } else {
                throw new APIErrorException("HTTP " + statusCode + ": " + errorMessage);
            }
        } catch (JsonSyntaxException e) {
            throw new JsonSyntaxException("Failed to parse error response for HTTP " + statusCode);
        } catch (ServerErrorGetTokenAuthException e) {
            throw new RuntimeException(e);
        }
    }

    private boolean isValidationError(String responseData) {
        return responseData.contains("validation")
                || responseData.contains("invalid")
                || responseData.contains("required");
    }

    protected String getToken() throws ServerErrorGetTokenAuthException {
        return getTokenService.getToken();
    }
}
