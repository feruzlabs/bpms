package com.bpms.connectors.creditconveyer.connector;

import com.bpms.connectors.creditconveyer.connector.support.Io;
import com.bpms.connectors.creditconveyer.dto.v8.accountHistory.AccountHistory;
import com.bpms.connectors.creditconveyer.dto.v8.accountHistory.ResponseHistory;
import com.bpms.connectors.creditconveyer.dto.v8.activeAccounts.ActiveAccountResponse;
import com.bpms.connectors.creditconveyer.service.RefreshAccountHistoryV9Service;
import com.bpms.spi.connector.Connector;
import com.bpms.spi.connector.ConnectorContext;
import com.bpms.spi.connector.ConnectorDescriptor;
import com.bpms.spi.connector.ConnectorInputDesc;
import com.bpms.spi.connector.ConnectorResult;
import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** id = eski {@code @Component("RefreshAccountHistoryV9Connector")} */
public final class RefreshAccountHistoryV9Connector implements Connector {

    private final RefreshAccountHistoryV9Service service;
    private final Gson gson;

    public RefreshAccountHistoryV9Connector(RefreshAccountHistoryV9Service service, Gson gson) {
        this.service = service;
        this.gson = gson;
    }

    @Override
    public String id() {
        return "RefreshAccountHistoryV9Connector";
    }

    @Override
    public ConnectorResult execute(ConnectorContext ctx) {
        Map<String, Object> in = ctx.inputs();
        String token = Io.str(in.get("token"));
        Object activeAccountsObject = in.get("ActiveAccountObject");
        ActiveAccountResponse aa = gson.fromJson(gson.toJson(activeAccountsObject), ActiveAccountResponse.class);

        String isSuccessVar = Io.name(in.get("IsSuccessVarSet"));
        String errorMsgVar = Io.name(in.get("ErrorMsgVarSet"));
        String responseVar = Io.name(in.get("ResponseVarSet"));

        Map<String, Object> out = new HashMap<>();
        try {
            List<AccountHistory> responses = new ArrayList<>();
            for (String accountId : aa.getAccounts()) {
                AccountHistory history = service.refresh(token, Long.parseLong(accountId));
                responses.add(history);
            }
            ResponseHistory responseHistory = new ResponseHistory();
            responseHistory.setResponses(responses);
            Io.put(out, responseVar, responseHistory);
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
                "v9 refresh account history",
                List.of(
                        new ConnectorInputDesc("token", true, "string", "Scoring token"),
                        new ConnectorInputDesc("ActiveAccountObject", true, "object", "Active accounts object"),
                        new ConnectorInputDesc("IsSuccessVarSet", false, "string", "Success flag var name"),
                        new ConnectorInputDesc("ErrorMsgVarSet", false, "string", "Error message var name"),
                        new ConnectorInputDesc("ResponseVarSet", false, "string", "Full response var name")
                ),
                List.of());
    }
}
