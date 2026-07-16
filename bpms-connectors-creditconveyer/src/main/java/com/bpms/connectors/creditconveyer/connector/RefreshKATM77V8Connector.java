package com.bpms.connectors.creditconveyer.connector;

import com.bpms.connectors.creditconveyer.connector.support.Io;
import com.bpms.connectors.creditconveyer.exceptions.ServiceIsNotRefreshAbleException;
import com.bpms.connectors.creditconveyer.service.v8.RefreshKATM77V8Service;
import com.bpms.spi.connector.Connector;
import com.bpms.spi.connector.ConnectorContext;
import com.bpms.spi.connector.ConnectorDescriptor;
import com.bpms.spi.connector.ConnectorInputDesc;
import com.bpms.spi.connector.ConnectorResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** id = eski {@code @Component("RefreshKATM77V8Connector")} */
public final class RefreshKATM77V8Connector implements Connector {

    private final RefreshKATM77V8Service service;

    public RefreshKATM77V8Connector(RefreshKATM77V8Service service) {
        this.service = service;
    }

    @Override
    public String id() {
        return "RefreshKATM77V8Connector";
    }

    @Override
    public ConnectorResult execute(ConnectorContext ctx) {
        Map<String, Object> in = ctx.inputs();
        String token = Io.str(in.get("token"));
        String isSuccessVar = Io.name(in.get("IsSuccessVarSet"));
        String errorMsgVar = Io.name(in.get("ErrorMsgVarSet"));
        String responseVar = Io.name(in.get("ResponseVarSet"));
        String isCanGetKatm = Io.name(in.get("IsCanGetKATM"));
        String reasonOfKatmFailed = Io.name(in.get("ReasonOfKATMFailed"));

        Map<String, Object> out = new HashMap<>();
        try {
            var response = service.refresh(token);
            Io.put(out, responseVar, response);
            Io.put(out, isSuccessVar, true);
            Io.put(out, isCanGetKatm, true);
        } catch (ServiceIsNotRefreshAbleException e) {
            Io.put(out, isCanGetKatm, false);
            Io.put(out, reasonOfKatmFailed, e.getMessage());
        } catch (Exception e) {
            Io.put(out, isCanGetKatm, true);
            Io.put(out, isSuccessVar, false);
            Io.put(out, errorMsgVar, e.getMessage());
        }
        return ConnectorResult.ok(out);
    }

    @Override
    public ConnectorDescriptor describe() {
        return new ConnectorDescriptor(id(), "v8 refresh KATM 77",
                List.of(
                        new ConnectorInputDesc("token", true, "string", "Scoring token"),
                        new ConnectorInputDesc("IsSuccessVarSet", false, "string", "Success flag var name"),
                        new ConnectorInputDesc("ErrorMsgVarSet", false, "string", "Error message var name"),
                        new ConnectorInputDesc("ResponseVarSet", false, "string", "Full response var name"),
                        new ConnectorInputDesc("IsCanGetKATM", false, "string", "Can get KATM var name"),
                        new ConnectorInputDesc("ReasonOfKATMFailed", false, "string", "KATM fail reason var name")
                ),
                List.of());
    }
}
