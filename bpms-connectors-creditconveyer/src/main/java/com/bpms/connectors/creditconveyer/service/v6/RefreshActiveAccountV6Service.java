package com.bpms.connectors.creditconveyer.service.v6;

import com.bpms.connectors.creditconveyer.dto.ErrorResponseDTO;
import com.bpms.connectors.creditconveyer.dto.v6.RefreshActiveAccountResponseDTO;
import com.bpms.connectors.creditconveyer.exceptions.*;
import com.bpms.connectors.creditconveyer.service.RefreshService;
import com.bpms.connectors.creditconveyer.vo.ConveyorPaths;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
@RequiredArgsConstructor
public class RefreshActiveAccountV6Service {
    @Value("${creditconveyer.endpoint}")
    String endpoint;

    private final RefreshService<RefreshActiveAccountResponseDTO, ErrorResponseDTO> refreshService;

    public RefreshActiveAccountResponseDTO refresh(String token)
            throws ServerErrorGetTokenAuthException, IOException, RefreshServiceErrorException, NotAuthorizedCreateNewRequestException {
        try {
            return refreshService.refresh(endpoint, ConveyorPaths.REFRESH_ACTIVE_ACCOUNT_DATA_V6, token,
                    RefreshActiveAccountResponseDTO.class, ErrorResponseDTO.class);
        } catch (APIErrorException | APINotFoundException | APIUnprocessableEntityException e) {
            throw new RefreshServiceErrorException(e.getMessage());
        } catch (APIUnauthorizedException e) {
            throw new NotAuthorizedCreateNewRequestException(e.getMessage());
        }
    }
}
