package com.bpms.connectors.creditconveyer.service.v8;

import com.bpms.connectors.creditconveyer.dto.CallBackTuneSendV6DTO;
import com.bpms.connectors.creditconveyer.dto.ErrorResponseDTO;
import com.bpms.connectors.creditconveyer.exceptions.*;
import com.bpms.connectors.creditconveyer.service.SendRequest2XaznaService;
import com.bpms.connectors.creditconveyer.vo.ConveyorPaths;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;


@Service
@RequiredArgsConstructor
public class SendRequestV5XaznaService {
    @Value("${creditconveyer.tune.endpoint.prod}")
    String endpoint;

    private final SendRequest2XaznaService<CallBackTuneSendV6DTO, Object, ErrorResponseDTO> callBackService; // Inject this service
    public Object send(CallBackTuneSendV6DTO request) throws APIUnprocessableEntityException, ServerErrorGetTokenAuthException, APIErrorException, ApiInternalErrorException, ValidationErrorException, APIBadRequestException, APIUnauthorizedException, IOException, APINotFoundException {
        return callBackService.send(endpoint, ConveyorPaths.SEND_XAZNA_V4, request, Object.class, ErrorResponseDTO.class);
    }
}
