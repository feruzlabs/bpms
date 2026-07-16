package com.bpms.connectors.creditconveyer.service;

import com.bpms.connectors.creditconveyer.dto.ErrorResponseDTO;
import com.bpms.connectors.creditconveyer.dto.v9.DwhMastercardFeaturesResponseV9DTO;
import com.bpms.connectors.creditconveyer.exceptions.*;
import com.bpms.connectors.creditconveyer.http.ConveyorClientV9;
import com.bpms.connectors.creditconveyer.vo.ConveyorPathsV9;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
public class DwhMastercardFeaturesV9Service {

    private final String endpoint;
    private final ConveyorClientV9 client;

    public DwhMastercardFeaturesV9Service(
            @Value("${creditconveyer.endpoint}") String endpoint,
            ConveyorClientV9 client
    ) {
        this.endpoint = endpoint;
        this.client = client;
    }

    public DwhMastercardFeaturesResponseV9DTO refresh(String token)
            throws IOException, RefreshServiceErrorException, NotAuthorizedCreateNewRequestException {
        try {
            String url = endpoint + String.format(ConveyorPathsV9.DWH_MASTERCARD_FEATURES, token);
            return client.get(url, DwhMastercardFeaturesResponseV9DTO.class, ErrorResponseDTO.class);
        } catch (APIErrorException | APINotFoundException | APIUnprocessableEntityException | APIGoneException e) {
            throw new RefreshServiceErrorException(e.getMessage());
        } catch (APIUnauthorizedException e) {
            throw new NotAuthorizedCreateNewRequestException(e.getMessage());
        }
    }
}
