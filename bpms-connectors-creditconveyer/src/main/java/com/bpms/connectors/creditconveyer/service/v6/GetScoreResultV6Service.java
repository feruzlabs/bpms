package com.bpms.connectors.creditconveyer.service.v6;

import com.bpms.connectors.creditconveyer.dto.ErrorResponseDTO;
import com.bpms.connectors.creditconveyer.dto.v5.CreateCreditRequestAndClientResponseInfoV5DTO;
import com.bpms.connectors.creditconveyer.exceptions.*;
import com.bpms.connectors.creditconveyer.service.GetResultService;
import com.bpms.connectors.creditconveyer.vo.ConveyorPaths;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
@RequiredArgsConstructor
public class GetScoreResultV6Service {
    @Value("${creditconveyer.endpoint}")
    String endpoint;

    private final GetResultService<CreateCreditRequestAndClientResponseInfoV5DTO, ErrorResponseDTO> refreshService;

    public CreateCreditRequestAndClientResponseInfoV5DTO refresh(String token)
            throws ServerErrorGetTokenAuthException, IOException, RefreshServiceErrorException, NotAuthorizedCreateNewRequestException {
        try {
            return refreshService.result(endpoint, ConveyorPaths.GET_REQUEST_RESULT_V6, token,
                    CreateCreditRequestAndClientResponseInfoV5DTO.class, ErrorResponseDTO.class);
        } catch (APIErrorException | APINotFoundException | APIUnprocessableEntityException e) {
            throw new RefreshServiceErrorException(e.getMessage());
        } catch (APIUnauthorizedException e) {
            throw new NotAuthorizedCreateNewRequestException(e.getMessage());
        }
    }
}
