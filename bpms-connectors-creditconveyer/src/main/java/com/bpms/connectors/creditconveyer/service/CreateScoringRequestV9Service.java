package com.bpms.connectors.creditconveyer.service;

import com.bpms.connectors.creditconveyer.dto.CreateNewCreditRequestAndClientResponseV6DTO;
import com.bpms.connectors.creditconveyer.dto.ErrorResponseDTO;
import com.bpms.connectors.creditconveyer.dto.v8.CreateNewRequestV8DTO;
import com.bpms.connectors.creditconveyer.exceptions.*;
import com.bpms.connectors.creditconveyer.http.ConveyorClientV9;
import com.bpms.connectors.creditconveyer.vo.ConveyorPathsV9;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
public class CreateScoringRequestV9Service {

    private final String endpoint;
    private final ConveyorClientV9 client;

    public CreateScoringRequestV9Service(
            @Value("${creditconveyer.endpoint}") String endpoint,
            ConveyorClientV9 client
    ) {
        this.endpoint = endpoint;
        this.client = client;
    }

    public CreateNewCreditRequestAndClientResponseV6DTO create(CreateNewRequestV8DTO request)
            throws ClientNotFoundIIBException,
            WrongDataCreateNewRequestException,
            ServerErrorCreateNewRequestException,
            NotAuthorizedCreateNewRequestException,
            Exception {
        try {
            return client.post(
                    endpoint + ConveyorPathsV9.CREATE_REQUEST,
                    request,
                    CreateNewCreditRequestAndClientResponseV6DTO.class,
                    ErrorResponseDTO.class);
        } catch (IOException | APIErrorException | ValidationErrorException e) {
            throw new Exception(e.getMessage());
        } catch (APIUnprocessableEntityException | APINotFoundException e) {
            throw new ClientNotFoundIIBException(e.getMessage());
        } catch (ApiInternalErrorException e) {
            throw new ServerErrorCreateNewRequestException(e.getMessage());
        } catch (APIBadRequestException e) {
            throw new WrongDataCreateNewRequestException(e.getMessage());
        } catch (APIUnauthorizedException e) {
            throw new NotAuthorizedCreateNewRequestException(e.getMessage());
        }
    }
}
