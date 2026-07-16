package com.bpms.connectors.creditconveyer.service.v8;


import com.bpms.connectors.creditconveyer.dto.ErrorResponseDTO;
import com.bpms.connectors.creditconveyer.dto.v7.ScoringLogV7DTO;
import com.bpms.connectors.creditconveyer.exceptions.*;
import com.bpms.connectors.creditconveyer.service.GetResultService;
import com.bpms.connectors.creditconveyer.vo.ConveyorPaths;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
@RequiredArgsConstructor
public class GetScoreLogV8Service {
    @Value("${creditconveyer.endpoint}")
    String endpoint;

    private final GetResultService<ScoringLogV7DTO, ErrorResponseDTO> refreshService; // Inject this service

    public ScoringLogV7DTO refresh(String token) throws ServerErrorGetTokenAuthException, IOException, RefreshServiceErrorException, NotAuthorizedCreateNewRequestException {
        try {
            return refreshService.result(endpoint, ConveyorPaths.GET_CLIENT_INFO_V8, token, ScoringLogV7DTO.class, ErrorResponseDTO.class);
        } catch (APIErrorException | APINotFoundException | APIUnprocessableEntityException e) {
            throw new RefreshServiceErrorException(e.getMessage());
        } catch (APIUnauthorizedException e) {
            throw new NotAuthorizedCreateNewRequestException(e.getMessage());
        }
    }
}
