package com.bpms.connectors.creditconveyer.service.v6;

import com.bpms.connectors.creditconveyer.dto.CreateNewCreditRequestAndClientResponseV6DTO;
import com.bpms.connectors.creditconveyer.dto.ErrorResponseDTO;
import com.bpms.connectors.creditconveyer.dto.v6.CreateNewRequestV6DTO;
import com.bpms.connectors.creditconveyer.exceptions.*;
import com.bpms.connectors.creditconveyer.service.CreateRequestService;
import com.bpms.connectors.creditconveyer.vo.ConveyorPaths;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
@RequiredArgsConstructor
public class CreateScoringRequestV6Service {
    @Value("${creditconveyer.endpoint}")
    String endpoint;

    private final CreateRequestService<CreateNewRequestV6DTO, CreateNewCreditRequestAndClientResponseV6DTO, ErrorResponseDTO> createRequestService;

    public CreateNewCreditRequestAndClientResponseV6DTO create(CreateNewRequestV6DTO request)
            throws ClientNotFoundIIBException,
            WrongDataCreateNewRequestException,
            ServerErrorCreateNewRequestException,
            NotAuthorizedCreateNewRequestException,
            Exception {
        try {
            return createRequestService.create(endpoint, ConveyorPaths.CREATE_REQUEST_V6, request,
                    CreateNewCreditRequestAndClientResponseV6DTO.class, ErrorResponseDTO.class);
        } catch (IOException | ServerErrorGetTokenAuthException | APIErrorException | ValidationErrorException e) {
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
