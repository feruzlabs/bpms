package com.bpms.connectors.creditconveyer.http;

import com.bpms.connectors.creditconveyer.auth.ConveyorAuthProvider;
import com.bpms.connectors.creditconveyer.dto.ErrorResponseDTO;
import com.bpms.connectors.creditconveyer.exceptions.APIBadRequestException;
import com.bpms.connectors.creditconveyer.exceptions.APIErrorException;
import com.bpms.connectors.creditconveyer.exceptions.APIGoneException;
import com.bpms.connectors.creditconveyer.exceptions.APINotFoundException;
import com.bpms.connectors.creditconveyer.exceptions.APIUnauthorizedException;
import com.bpms.connectors.creditconveyer.exceptions.APIUnprocessableEntityException;
import com.bpms.connectors.creditconveyer.exceptions.ApiInternalErrorException;
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
public class ConveyorClientV9 {

    private static final String CONTENT_TYPE_JSON = "application/json";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse(CONTENT_TYPE_JSON);
    private static final int HTTP_BAD_REQUEST = 400;
    private static final int HTTP_UNAUTHORIZED = 401;
    private static final int HTTP_NOT_FOUND = 404;
    private static final int HTTP_GONE = 410;
    private static final int HTTP_UNPROCESSABLE = 422;
    private static final int HTTP_INTERNAL = 500;

    private final OkHttpClient httpClient;
    private final Gson gson;
    private final ObjectMapper objectMapper;
    private final ConveyorAuthProvider authProvider;

    public ConveyorClientV9(
            OkHttpClient creditConveyerHttpClient,
            Gson gson,
            ObjectMapper objectMapper,
            ConveyorAuthProvider authProvider
    ) {
        this.httpClient = creditConveyerHttpClient;
        this.gson = gson;
        this.objectMapper = objectMapper;
        this.authProvider = authProvider;
    }

    public <R, E extends ErrorResponseDTO> R get(String url, Class<R> responseType, Class<E> errorClassType)
            throws IOException,
            APINotFoundException,
            APIErrorException,
            APIUnprocessableEntityException,
            APIUnauthorizedException,
            APIGoneException {
        Request request = new Request.Builder()
                .url(url)
                .get()
                .addHeader("Accept", CONTENT_TYPE_JSON)
                .addHeader("Authorization", authProvider.authorizationHeader())
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            return processGetResponse(response, responseType, errorClassType);
        }
    }

    public <T, R, E extends ErrorResponseDTO> R post(String url, T body, Class<R> responseType, Class<E> errorClassType)
            throws IOException,
            APIUnprocessableEntityException,
            APIErrorException,
            ApiInternalErrorException,
            ValidationErrorException,
            APIBadRequestException,
            APIUnauthorizedException,
            APINotFoundException {
        String requestBody = objectMapper.writeValueAsString(body);
        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(requestBody, JSON_MEDIA_TYPE))
                .addHeader("Authorization", authProvider.authorizationHeader())
                .addHeader("Content-Type", CONTENT_TYPE_JSON)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            return processPostResponse(response, responseType, errorClassType);
        }
    }

    private <R, E extends ErrorResponseDTO> R processGetResponse(Response response, Class<R> responseType, Class<E> errorClassType)
            throws IOException,
            APINotFoundException,
            APIErrorException,
            APIUnprocessableEntityException,
            APIUnauthorizedException,
            APIGoneException {
        ResponseBody responseBody = response.body();
        if (responseBody == null) {
            throw new APIErrorException("Empty response received");
        }
        String responseData = responseBody.string();
        if (response.isSuccessful()) {
            return gson.fromJson(responseData, responseType);
        }

        E errorResponse = gson.fromJson(responseData, errorClassType);
        String message = errorResponse != null ? errorResponse.getMessage() : "Unknown error";

        if (response.code() == HTTP_NOT_FOUND) {
            throw new APINotFoundException(message);
        }
        if (response.code() == HTTP_UNAUTHORIZED) {
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

    private <R, E extends ErrorResponseDTO> R processPostResponse(Response response, Class<R> responseType, Class<E> errorClassType)
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

        handlePostError(response.code(), responseData, errorClassType);
        return null;
    }

    private <E extends ErrorResponseDTO> void handlePostError(int statusCode, String responseData, Class<E> errorClassType)
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
        }
    }

    private boolean isValidationError(String responseData) {
        return responseData.contains("validation")
                || responseData.contains("invalid")
                || responseData.contains("required");
    }
}
