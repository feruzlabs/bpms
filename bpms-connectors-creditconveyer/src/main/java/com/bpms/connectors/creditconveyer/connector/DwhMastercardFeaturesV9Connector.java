package com.bpms.connectors.creditconveyer.connector;

import com.bpms.connectors.creditconveyer.connector.support.Io;
import com.bpms.connectors.creditconveyer.service.DwhMastercardFeaturesV9Service;
import com.bpms.connectors.creditconveyer.dto.v9.DwhMastercardFeaturesResponseV9DTO;
import com.bpms.spi.connector.Connector;
import com.bpms.spi.connector.ConnectorContext;
import com.bpms.spi.connector.ConnectorDescriptor;
import com.bpms.spi.connector.ConnectorInputDesc;
import com.bpms.spi.connector.ConnectorResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** id = eski `@Component("DwhMastercardFeaturesV9Connector")` — BPMN camunda:connectorId bilan ayni. */
public final class DwhMastercardFeaturesV9Connector implements Connector {

    private final DwhMastercardFeaturesV9Service service;

    public DwhMastercardFeaturesV9Connector(DwhMastercardFeaturesV9Service service) {
        this.service = service;
    }

    @Override
    public String id() {
        return "DwhMastercardFeaturesV9Connector";
    }

    @Override
    public ConnectorResult execute(ConnectorContext ctx) {
        Map<String, Object> in = ctx.inputs();
        String token = Io.str(in.get("token"));
        String isSuccessVar = Io.name(in.get("IsSuccessVarSet"));
        String errorMsgVar = Io.name(in.get("ErrorMsgVarSet"));
        String responseVar = Io.name(in.get("ResponseVarSet"));

        Map<String, Object> out = new HashMap<>();
        try {
            var response = service.refresh(token);
            Io.put(out, responseVar, response);
            Io.put(out, isSuccessVar, true);
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
                "DwhMastercardFeaturesV9Connector",
                List.of(
                        new ConnectorInputDesc("token", true, "string", "Scoring token"),
                        new ConnectorInputDesc("IsSuccessVarSet", false, "string", "Success flag var name"),
                        new ConnectorInputDesc("ErrorMsgVarSet", false, "string", "Error message var name"),
                        new ConnectorInputDesc("ResponseVarSet", false, "string", "Full response var name")
                ),
                List.of());
    }
}
