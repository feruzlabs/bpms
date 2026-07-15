package com.bpms.connectors.creditconveyer.service;

import com.bpms.connectors.creditconveyer.dto.ErrorResponseDTO;
import com.bpms.connectors.creditconveyer.dto.v8.CreateCreditRequestAndClientResponseInfoV8DTO;
import com.bpms.connectors.creditconveyer.exceptions.APIErrorException;
import com.bpms.connectors.creditconveyer.exceptions.APIGoneException;
import com.bpms.connectors.creditconveyer.exceptions.APINotFoundException;
import com.bpms.connectors.creditconveyer.exceptions.APIUnauthorizedException;
import com.bpms.connectors.creditconveyer.exceptions.APIUnprocessableEntityException;
import com.bpms.connectors.creditconveyer.exceptions.NotAuthorizedCreateNewRequestException;
import com.bpms.connectors.creditconveyer.exceptions.RefreshServiceErrorException;
import com.bpms.connectors.creditconveyer.http.ConveyorClientV9;
import com.bpms.connectors.creditconveyer.vo.ConveyorPathsV9;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
public class GetScoreResultV9Service {

    private final String endpoint;
    private final ConveyorClientV9 client;

    public GetScoreResultV9Service(
            @Value("${creditconveyer.endpoint}") String endpoint,
            ConveyorClientV9 client
    ) {
        this.endpoint = endpoint;
        this.client = client;
    }

    public CreateCreditRequestAndClientResponseInfoV8DTO refresh(String token)
            throws IOException, RefreshServiceErrorException, NotAuthorizedCreateNewRequestException {
        try {
            return client.get(
                    endpoint + ConveyorPathsV9.SCORE + token,
                    CreateCreditRequestAndClientResponseInfoV8DTO.class,
                    ErrorResponseDTO.class);
        } catch (APIErrorException | APINotFoundException | APIUnprocessableEntityException | APIGoneException e) {
            throw new RefreshServiceErrorException(e.getMessage());
        } catch (APIUnauthorizedException e) {
            throw new NotAuthorizedCreateNewRequestException(e.getMessage());
        }
    }
}
