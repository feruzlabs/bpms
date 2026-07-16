package com.bpms.connectors.creditconveyer.service.v8;

import com.bpms.connectors.creditconveyer.dto.ErrorResponseDTO;
import com.bpms.connectors.creditconveyer.dto.v8.accountHistory.AccountHistory;
import com.bpms.connectors.creditconveyer.dto.v8.activeAccounts.ActiveAccountResponse;
import com.bpms.connectors.creditconveyer.exceptions.*;
import com.bpms.connectors.creditconveyer.service.RefreshService;
import com.bpms.connectors.creditconveyer.vo.ConveyorPaths;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.text.MessageFormat;

@Service
@RequiredArgsConstructor
public class RefreshAccountHistoryV8Service {
    @Value("${creditconveyer.endpoint}")
    String endpoint;

    private final RefreshService<AccountHistory, ErrorResponseDTO> refreshService; // Inject this service

    public AccountHistory refresh(String token, long accountId) throws ServerErrorGetTokenAuthException, IOException, RefreshServiceErrorException, NotAuthorizedCreateNewRequestException {
        try {
            String url = String.format(ConveyorPaths.REFRESH_ACCOUNT_HISTORY_DATA_V8, token, accountId);
            return refreshService.refresh(endpoint, url, "", AccountHistory.class, ErrorResponseDTO.class);
        } catch (APIErrorException | APINotFoundException | APIUnprocessableEntityException e) {
            throw new RefreshServiceErrorException(e.getMessage());
        } catch (APIUnauthorizedException e) {
            throw new NotAuthorizedCreateNewRequestException(e.getMessage());
        }
    }
}
