package com.bpms.connectors.creditconveyer.service.v6;

import com.bpms.connectors.creditconveyer.dto.ErrorResponseDTO;
import com.bpms.connectors.creditconveyer.exceptions.*;
import com.bpms.connectors.creditconveyer.service.RefreshService;
import com.bpms.connectors.creditconveyer.vo.ConveyorPaths;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
@RequiredArgsConstructor
public class CloseKATMV6Service {
    @Value("${creditconveyer.endpoint}")
    String endpoint;

    private final RefreshService<Object, ErrorResponseDTO> refreshService;

    public Object refresh(String token) throws ServerErrorGetTokenAuthException,
            IOException,
            KATMWaitingException, NotAuthorizedCreateNewRequestException, RefreshServiceErrorException {
        try {
            return refreshService.refresh(endpoint, ConveyorPaths.CLOSE_KATM_REQUEST, token,
                    Object.class, ErrorResponseDTO.class);
        } catch (APIUnprocessableEntityException e) {
            throw new KATMWaitingException(e.getMessage());
        } catch (APIErrorException | APINotFoundException e) {
            throw new RefreshServiceErrorException(e.getMessage());
        } catch (APIUnauthorizedException e) {
            throw new NotAuthorizedCreateNewRequestException(e.getMessage());
        }
    }
}
