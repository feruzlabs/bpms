package com.bpms.connectors.creditconveyer.service;

import com.bpms.connectors.creditconveyer.dto.ErrorResponseDTO;
import com.bpms.connectors.creditconveyer.dto.RefreshServiceResponseDTO;
import com.bpms.connectors.creditconveyer.exceptions.*;
import com.bpms.connectors.creditconveyer.http.ConveyorClientV9;
import com.bpms.connectors.creditconveyer.vo.ConveyorPathsV9;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
public class RefreshKATM77V9Service {

    private final String endpoint;
    private final ConveyorClientV9 client;

    public RefreshKATM77V9Service(
            @Value("${creditconveyer.endpoint}") String endpoint,
            ConveyorClientV9 client
    ) {
        this.endpoint = endpoint;
        this.client = client;
    }

    public RefreshServiceResponseDTO refresh(String token)
            throws IOException,
            KATMWaitingException,
            NotAuthorizedCreateNewRequestException,
            RefreshServiceErrorException,
            ServiceIsNotRefreshAbleException {
        try {
            return client.get(
                    endpoint + ConveyorPathsV9.REFRESH_KATM_77 + token,
                    RefreshServiceResponseDTO.class,
                    ErrorResponseDTO.class);
        } catch (APIUnprocessableEntityException e) {
            throw new KATMWaitingException(e.getMessage());
        } catch (APIErrorException | APINotFoundException e) {
            throw new RefreshServiceErrorException(e.getMessage());
        } catch (APIUnauthorizedException e) {
            throw new NotAuthorizedCreateNewRequestException(e.getMessage());
        } catch (APIGoneException e) {
            throw new ServiceIsNotRefreshAbleException(e.getMessage());
        }
    }
}
