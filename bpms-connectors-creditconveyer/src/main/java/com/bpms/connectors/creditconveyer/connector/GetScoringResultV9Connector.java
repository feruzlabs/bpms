package com.bpms.connectors.creditconveyer.connector;

import com.bpms.connectors.creditconveyer.connector.support.Io;
import com.bpms.connectors.creditconveyer.service.GetScoreResultV9Service;
import com.bpms.spi.connector.Connector;
import com.bpms.spi.connector.ConnectorContext;
import com.bpms.spi.connector.ConnectorDescriptor;
import com.bpms.spi.connector.ConnectorInputDesc;
import com.bpms.spi.connector.ConnectorResult;
import com.google.gson.Gson;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** id = eski {@code @Component("GetScoringResultV9Connector")} — BPMN camunda:connectorId bilan ayni. */
public final class GetScoringResultV9Connector implements Connector {

    private final GetScoreResultV9Service service;
    private final Gson gson;

    public GetScoringResultV9Connector(GetScoreResultV9Service service, Gson gson) {
        this.service = service;
        this.gson = gson;
    }

    @Override
    public String id() {
        return "GetScoringResultV9Connector";
    }

    @Override
    public ConnectorResult execute(ConnectorContext ctx) {
        Map<String, Object> in = ctx.inputs();
        String token = Io.str(in.get("token"));
        String isSuccessVar = Io.name(in.get("IsSuccessVarSet"));
        String errorMsgVar = Io.name(in.get("ErrorMsgVarSet"));
        String responseVar = Io.name(in.get("ResponseVarSet"));
        String scoringMsgVar = Io.name(in.get("ScoringMessage"));
        String avgIncomeVar = Io.name(in.get("ClientAvgIncomeSum"));
        String creditSumVar = Io.name(in.get("ClientCreditSumVarSet"));
        String statusVar = Io.name(in.get("ClientStatusVarSet"));

        Map<String, Object> out = new HashMap<>();
        try {
            var response = service.refresh(token);
            Io.put(out, responseVar, response);
            Io.put(out, isSuccessVar, true);
            Io.put(out, creditSumVar, gson.toJson(response.getLoans()));
            Io.put(out, avgIncomeVar, (long) response.getClientMonthlyIncome());
            Io.put(out, scoringMsgVar, response.getMessage());
            Io.put(out, statusVar, response.getStatus());
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
                "v9 scoring result (PHP /v9/mobile/request/score/{token})",
                List.of(
                        new ConnectorInputDesc("token", true, "string", "Scoring token"),
                        new ConnectorInputDesc("IsSuccessVarSet", false, "string", "Success flag var name"),
                        new ConnectorInputDesc("ErrorMsgVarSet", false, "string", "Error message var name"),
                        new ConnectorInputDesc("ResponseVarSet", false, "string", "Full response var name"),
                        new ConnectorInputDesc("ScoringMessage", false, "string", "Message var name"),
                        new ConnectorInputDesc("ClientAvgIncomeSum", false, "string", "Avg income var name"),
                        new ConnectorInputDesc("ClientCreditSumVarSet", false, "string", "Loans JSON var name"),
                        new ConnectorInputDesc("ClientStatusVarSet", false, "string", "Status var name")
                ),
                List.of());
    }
}
