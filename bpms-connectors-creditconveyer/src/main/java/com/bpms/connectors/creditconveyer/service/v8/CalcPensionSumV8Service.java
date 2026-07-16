package com.bpms.connectors.creditconveyer.service.v8;

import com.bpms.connectors.creditconveyer.dto.ErrorResponseDTO;
import com.bpms.connectors.creditconveyer.dto.v8.calc.CalcPensionResponse;
import com.bpms.connectors.creditconveyer.exceptions.*;
import com.bpms.connectors.creditconveyer.service.RefreshService;
import com.bpms.connectors.creditconveyer.vo.ConveyorPaths;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
@RequiredArgsConstructor
public class CalcPensionSumV8Service {
    @Value("${creditconveyer.endpoint}")
    String endpoint;

    private final RefreshService<CalcPensionResponse, ErrorResponseDTO> refreshService; // Inject this service

    public CalcPensionResponse refresh(String token) throws ServerErrorGetTokenAuthException,
            IOException,
            CheckKATMBanFailedException,  NotAuthorizedCreateNewRequestException, RefreshServiceErrorException {
        try {
            return refreshService.refresh(endpoint, ConveyorPaths.CALC_PENSION_SUM_V8, token, CalcPensionResponse.class, ErrorResponseDTO.class);
        } catch (APIUnprocessableEntityException e) {
            throw new CheckKATMBanFailedException(e.getMessage());
        } catch (APIErrorException|APINotFoundException e) {
            throw new RefreshServiceErrorException(e.getMessage());
        } catch (APIUnauthorizedException e) {
            throw new NotAuthorizedCreateNewRequestException(e.getMessage());
        }
    }
}
