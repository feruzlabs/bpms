package com.bpms.connectors.creditconveyer.service;

import com.bpms.connectors.creditconveyer.dto.ErrorResponseDTO;
import com.bpms.connectors.creditconveyer.dto.v7.ScoringLogV7DTO;
import com.bpms.connectors.creditconveyer.exceptions.*;
import com.bpms.connectors.creditconveyer.http.ConveyorClientV9;
import com.bpms.connectors.creditconveyer.vo.ConveyorPathsV9;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
public class GetClientInfoV9Service {

    private final String endpoint;
    private final ConveyorClientV9 client;

    public GetClientInfoV9Service(
            @Value("${creditconveyer.endpoint}") String endpoint,
            ConveyorClientV9 client
    ) {
        this.endpoint = endpoint;
        this.client = client;
    }

    public ScoringLogV7DTO refresh(String token)
            throws IOException, RefreshServiceErrorException, NotAuthorizedCreateNewRequestException {
        try {
            return client.get(
                    endpoint + ConveyorPathsV9.CLIENT_INFO + token,
                    ScoringLogV7DTO.class,
                    ErrorResponseDTO.class);
        } catch (APIErrorException | APINotFoundException | APIUnprocessableEntityException | APIGoneException e) {
            throw new RefreshServiceErrorException(e.getMessage());
        } catch (APIUnauthorizedException e) {
            throw new NotAuthorizedCreateNewRequestException(e.getMessage());
        }
    }
}
