package com.bpms.connectors.creditconveyer.connector;

import com.bpms.connectors.creditconveyer.connector.support.Io;
import com.bpms.connectors.creditconveyer.dto.CreateNewCreditRequestAndClientResponseV6DTO;
import com.bpms.connectors.creditconveyer.dto.v8.CreateNewRequestV8DTO;
import com.bpms.connectors.creditconveyer.exceptions.ClientNotFoundIIBException;
import com.bpms.connectors.creditconveyer.service.CreateScoringRequestV9Service;
import com.bpms.spi.connector.Connector;
import com.bpms.spi.connector.ConnectorContext;
import com.bpms.spi.connector.ConnectorDescriptor;
import com.bpms.spi.connector.ConnectorInputDesc;
import com.bpms.spi.connector.ConnectorResult;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** id = eski {@code @Component("CreateRequestAndClientV9Connector")} */
public final class CreateRequestAndClientV9Connector implements Connector {

    private final CreateScoringRequestV9Service requestService;

    public CreateRequestAndClientV9Connector(CreateScoringRequestV9Service requestService) {
        this.requestService = requestService;
    }

    @Override
    public String id() {
        return "CreateRequestAndClientV9Connector";
    }

    @Override
    public ConnectorResult execute(ConnectorContext ctx) {
        Locale.setDefault(Locale.forLanguageTag("uz"));
        Map<String, Object> in = ctx.inputs();

        String isSuccessVar = Io.name(in.get("IsSuccessVarSet"));
        String errorMsgVar = Io.name(in.get("ErrorMsgVarSet"));
        String responseVar = Io.name(in.get("ResponseVarSet"));
        String stopVar = Io.name(in.get("StopVarSet"));
        String tokenVar = Io.name(in.get("TokenVarSet"));
        String requestVar = Io.name(in.get("RequestVarSet"));

        boolean isPensioner = false;
        Object isPensionerObj = in.get("isPensioner");
        if (isPensionerObj instanceof Boolean b) {
            isPensioner = b;
        } else if (isPensionerObj instanceof String s && !s.isEmpty()) {
            isPensioner = Boolean.parseBoolean(s);
        }

        Map<String, Object> out = new HashMap<>();
        try {
            CreateNewRequestV8DTO dto = CreateNewRequestV8DTO.builder()
                    .pin(Io.str(in.get("pin")))
                    .passport(Io.str(in.get("passport")))
                    .requestId(Io.str(in.get("requestId")))
                    .productCodeAbs(Io.str(in.get("productCodeAbs")))
                    .term(Integer.parseInt(Io.str(in.get("term"))))
                    .grace(Integer.parseInt(Io.str(in.get("grace"))))
                    .amount(Long.parseLong(Io.str(in.get("amount"))))
                    .percent(Double.parseDouble(Io.str(in.get("percent"))))
                    .build();
            dto.setPensioner(isPensioner);

            Io.put(out, requestVar, dto);

            CreateNewCreditRequestAndClientResponseV6DTO response = requestService.create(dto);
            Io.put(out, responseVar, response);
            Io.put(out, isSuccessVar, true);
            Io.put(out, tokenVar, response.getToken());
        } catch (ClientNotFoundIIBException e) {
            Io.put(out, isSuccessVar, false);
            Io.put(out, stopVar, true);
            Io.put(out, errorMsgVar, e.getMessage());
        } catch (Exception e) {
            Io.put(out, isSuccessVar, false);
            Io.put(out, errorMsgVar, e.getMessage());
        }
        return ConnectorResult.ok(out);
    }

    @Override
    public ConnectorDescriptor describe() {
        return new ConnectorDescriptor(
                id(),
                "v9 create scoring request and client",
                List.of(
                        new ConnectorInputDesc("pin", true, "string", "PINFL"),
                        new ConnectorInputDesc("passport", true, "string", "Passport"),
                        new ConnectorInputDesc("requestId", true, "string", "Request ID"),
                        new ConnectorInputDesc("productCodeAbs", true, "string", "ABS product code"),
                        new ConnectorInputDesc("term", true, "string", "Term months"),
                        new ConnectorInputDesc("grace", true, "string", "Grace period"),
                        new ConnectorInputDesc("amount", true, "string", "Amount"),
                        new ConnectorInputDesc("percent", true, "string", "Percent"),
                        new ConnectorInputDesc("isPensioner", false, "boolean", "Pensioner flag"),
                        new ConnectorInputDesc("IsSuccessVarSet", false, "string", "Success flag var name"),
                        new ConnectorInputDesc("ErrorMsgVarSet", false, "string", "Error message var name"),
                        new ConnectorInputDesc("ResponseVarSet", false, "string", "Full response var name"),
                        new ConnectorInputDesc("StopVarSet", false, "string", "Stop flag var name"),
                        new ConnectorInputDesc("TokenVarSet", false, "string", "Token var name"),
                        new ConnectorInputDesc("RequestVarSet", false, "string", "Request DTO var name")
                ),
                List.of());
    }
}
