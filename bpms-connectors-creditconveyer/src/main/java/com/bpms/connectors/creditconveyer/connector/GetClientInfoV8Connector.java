package com.bpms.connectors.creditconveyer.connector;

import com.bpms.connectors.creditconveyer.connector.support.Io;
import com.bpms.connectors.creditconveyer.dto.v7.ScoringLogV7DTO;
import com.bpms.connectors.creditconveyer.service.v8.GetScoreLogV8Service;
import com.bpms.spi.connector.Connector;
import com.bpms.spi.connector.ConnectorContext;
import com.bpms.spi.connector.ConnectorDescriptor;
import com.bpms.spi.connector.ConnectorInputDesc;
import com.bpms.spi.connector.ConnectorResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** id = eski {@code @Component("GetClientInfoV8Connector")} */
public final class GetClientInfoV8Connector implements Connector {

    private final GetScoreLogV8Service service;

    public GetClientInfoV8Connector(GetScoreLogV8Service service) {
        this.service = service;
    }

    @Override
    public String id() {
        return "GetClientInfoV8Connector";
    }

    @Override
    public ConnectorResult execute(ConnectorContext ctx) {
        Map<String, Object> in = ctx.inputs();
        String token = Io.str(in.get("token"));
        String isSuccessVar = Io.name(in.get("IsSuccessVarSet"));
        String errorMsgVar = Io.name(in.get("ErrorMsgVarSet"));
        String responseVar = Io.name(in.get("ResponseVarSet"));
        String claimIdVar = Io.name(in.get("claimId"));

        Map<String, Object> out = new HashMap<>();
        try {
            ScoringLogV7DTO resp = service.refresh(token);
            String claimId = "";
            try {
                claimId = resp.getData().getClient().getData().getResponse().getLogs().getClientData().getKatm().getClaimId();
            } catch (NullPointerException ignored) {
                claimId = "";
            }
            Io.put(out, responseVar, resp);
            Io.put(out, claimIdVar, claimId);
            Io.put(out, isSuccessVar, true);
        } catch (Exception e) {
            Io.put(out, isSuccessVar, false);
            Io.put(out, errorMsgVar, e.getMessage());
        }
        return ConnectorResult.ok(out);
    }

    @Override
    public ConnectorDescriptor describe() {
        return new ConnectorDescriptor(id(), "v8 get client info",
                List.of(
                        new ConnectorInputDesc("token", true, "string", "Scoring token"),
                        new ConnectorInputDesc("IsSuccessVarSet", false, "string", "Success flag var name"),
                        new ConnectorInputDesc("ErrorMsgVarSet", false, "string", "Error message var name"),
                        new ConnectorInputDesc("ResponseVarSet", false, "string", "Full response var name"),
                        new ConnectorInputDesc("claimId", false, "string", "KATM claimId var name")
                ),
                List.of());
    }
}
