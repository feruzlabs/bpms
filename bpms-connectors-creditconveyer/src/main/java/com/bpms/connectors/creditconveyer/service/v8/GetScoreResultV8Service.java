package com.bpms.connectors.creditconveyer.service.v8;


import com.bpms.connectors.creditconveyer.dto.ErrorResponseDTO;
import com.bpms.connectors.creditconveyer.dto.v5.CreateCreditRequestAndClientResponseInfoV5DTO;
import com.bpms.connectors.creditconveyer.dto.v8.CreateCreditRequestAndClientResponseInfoV8DTO;
import com.bpms.connectors.creditconveyer.exceptions.*;
import com.bpms.connectors.creditconveyer.service.GetResultService;
import com.bpms.connectors.creditconveyer.vo.ConveyorPaths;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
@RequiredArgsConstructor
public class GetScoreResultV8Service {
    @Value("${creditconveyer.endpoint}")
    String endpoint;

    private final GetResultService<CreateCreditRequestAndClientResponseInfoV8DTO, ErrorResponseDTO> refreshService; // Inject this service

    public CreateCreditRequestAndClientResponseInfoV8DTO refresh(String token) throws ServerErrorGetTokenAuthException, IOException, RefreshServiceErrorException, NotAuthorizedCreateNewRequestException {
        try {
            return refreshService.result(endpoint, ConveyorPaths.GET_REQUEST_RESULT_V8, token, CreateCreditRequestAndClientResponseInfoV8DTO.class, ErrorResponseDTO.class);
        } catch (APIErrorException | APINotFoundException | APIUnprocessableEntityException e) {
            throw new RefreshServiceErrorException(e.getMessage());
        } catch (APIUnauthorizedException e) {
            throw new NotAuthorizedCreateNewRequestException(e.getMessage());
        }
    }
}
