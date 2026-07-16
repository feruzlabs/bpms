package com.bpms.connectors.creditconveyer.connector;

import com.bpms.connectors.creditconveyer.connector.support.Io;
import com.bpms.connectors.creditconveyer.dto.CallBackTuneSendV6DTO;
import com.bpms.connectors.creditconveyer.service.v8.SendRequestV5XaznaService;
import com.bpms.spi.connector.Connector;
import com.bpms.spi.connector.ConnectorContext;
import com.bpms.spi.connector.ConnectorDescriptor;
import com.bpms.spi.connector.ConnectorInputDesc;
import com.bpms.spi.connector.ConnectorResult;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * id = eski {@code @Component("TuneSentMobileAPIV8Connector")}.
 * Stop-factor lookup (GetStopFactorByInstanceTokenIdServiceV7_1) stubbed as empty list —
 * full engine-coupled stop-factor service deferred to next phase.
 */
public final class TuneSentMobileAPIV8Connector implements Connector {

    private final SendRequestV5XaznaService xaznaService;
    private final Gson gson;

    public TuneSentMobileAPIV8Connector(SendRequestV5XaznaService xaznaService, Gson gson) {
        this.xaznaService = xaznaService;
        this.gson = gson;
    }

    @Override
    public String id() {
        return "TuneSentMobileAPIV8Connector";
    }

    @Override
    public ConnectorResult execute(ConnectorContext ctx) {
        Locale.setDefault(Locale.forLanguageTag("uz"));
        Map<String, Object> in = ctx.inputs();

        String passport = Io.str(in.get("passport"));
        String pinfl = Io.str(in.get("pinfl"));
        String requestId = Io.str(in.get("requestId"));
        String productId = Io.str(in.get("productId"));
        String status = Io.str(in.get("creditStatus"));
        String claimId = Io.str(in.get("claimId"));
        String creditLoans = Io.str(in.get("creditLoans"));
        String clientMonthlyIncome = Io.str(in.get("clientMonthlyIncome"));
        String message = Io.str(in.get("message"));
        String isSuccessVar = Io.name(in.get("IsSuccessVarSet"));
        String errorMsgVar = Io.name(in.get("ErrorMsgVarSet"));
        String responseVar = Io.name(in.get("ResponseVarSet"));
        String requestVar = Io.name(in.get("RequestVarSet"));

        if (creditLoans.contains("$")) {
            creditLoans = "[]";
        }
        if (claimId.contains("$")) {
            claimId = "";
        }
        if (clientMonthlyIncome.isBlank()) {
            clientMonthlyIncome = "0";
        }

        Type listType = new TypeToken<List<CallBackTuneSendV6DTO.AvailableLoanDTO>>() {}.getType();
        List<CallBackTuneSendV6DTO.AvailableLoanDTO> loans = gson.fromJson(creditLoans, listType);
        long clientAmountLong = Long.parseLong(clientMonthlyIncome);

        CallBackTuneSendV6DTO.KATMData katm = new CallBackTuneSendV6DTO.KATMData();
        katm.setClaimId(claimId);

        CallBackTuneSendV6DTO.Data paramsData = new CallBackTuneSendV6DTO.Data();
        paramsData.setLoans(loans);
        paramsData.setClientMonthlyIncome(clientAmountLong);
        paramsData.setMessage(message);
        paramsData.setKatm(katm);

        CallBackTuneSendV6DTO.Params params = new CallBackTuneSendV6DTO.Params();
        params.setPassport(passport);
        params.setPinfl(pinfl);
        params.setRequestId(requestId);
        params.setProductId(productId);
        params.setStatus(status);
        params.setData(paramsData);
        params.setStopFactors(Collections.emptyList());

        CallBackTuneSendV6DTO callBackTuneSendDTO = new CallBackTuneSendV6DTO();
        callBackTuneSendDTO.setId(1);
        callBackTuneSendDTO.setJsonrpc("2.0");
        callBackTuneSendDTO.setMethod("call.back");
        callBackTuneSendDTO.setParams(params);

        Map<String, Object> out = new HashMap<>();
        try {
            Io.put(out, requestVar, callBackTuneSendDTO);
            Object responseTune = xaznaService.send(callBackTuneSendDTO);
            Io.put(out, isSuccessVar, true);
            Io.put(out, responseVar, responseTune);
        } catch (Exception e) {
            Io.put(out, isSuccessVar, false);
            Io.put(out, errorMsgVar, e.getMessage());
        }
        return ConnectorResult.ok(out);
    }

    @Override
    public ConnectorDescriptor describe() {
        return new ConnectorDescriptor(id(), "v8 Tune/Xazna mobile callback",
                List.of(
                        new ConnectorInputDesc("passport", true, "string", "Passport"),
                        new ConnectorInputDesc("pinfl", true, "string", "PINFL"),
                        new ConnectorInputDesc("requestId", true, "string", "Request ID"),
                        new ConnectorInputDesc("productId", true, "string", "Product ID"),
                        new ConnectorInputDesc("creditStatus", true, "string", "Credit status"),
                        new ConnectorInputDesc("claimId", false, "string", "KATM claimId"),
                        new ConnectorInputDesc("creditLoans", false, "string", "Loans JSON"),
                        new ConnectorInputDesc("clientMonthlyIncome", false, "string", "Monthly income"),
                        new ConnectorInputDesc("message", false, "string", "Message"),
                        new ConnectorInputDesc("IsSuccessVarSet", false, "string", "Success flag var name"),
                        new ConnectorInputDesc("ErrorMsgVarSet", false, "string", "Error message var name"),
                        new ConnectorInputDesc("ResponseVarSet", false, "string", "Full response var name"),
                        new ConnectorInputDesc("RequestVarSet", false, "string", "Request DTO var name")
                ),
                List.of());
    }
}
