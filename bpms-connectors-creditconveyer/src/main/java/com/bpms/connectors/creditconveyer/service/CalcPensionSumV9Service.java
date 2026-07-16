package com.bpms.connectors.creditconveyer.service;

import com.bpms.connectors.creditconveyer.dto.ErrorResponseDTO;
import com.bpms.connectors.creditconveyer.dto.v8.calc.CalcPensionResponse;
import com.bpms.connectors.creditconveyer.exceptions.*;
import com.bpms.connectors.creditconveyer.http.ConveyorClientV9;
import com.bpms.connectors.creditconveyer.vo.ConveyorPathsV9;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
public class CalcPensionSumV9Service {

    private final String endpoint;
    private final ConveyorClientV9 client;

    public CalcPensionSumV9Service(
            @Value("${creditconveyer.endpoint}") String endpoint,
            ConveyorClientV9 client
    ) {
        this.endpoint = endpoint;
        this.client = client;
    }

    public CalcPensionResponse refresh(String token)
            throws IOException,
            CheckKATMBanFailedException,
            NotAuthorizedCreateNewRequestException,
            RefreshServiceErrorException {
        try {
            return client.get(
                    endpoint + ConveyorPathsV9.PENSION_CALC + token,
                    CalcPensionResponse.class,
                    ErrorResponseDTO.class);
        } catch (APIUnprocessableEntityException e) {
            throw new CheckKATMBanFailedException(e.getMessage());
        } catch (APIErrorException | APINotFoundException | APIGoneException e) {
            throw new RefreshServiceErrorException(e.getMessage());
        } catch (APIUnauthorizedException e) {
            throw new NotAuthorizedCreateNewRequestException(e.getMessage());
        }
    }
}
