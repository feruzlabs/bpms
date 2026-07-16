package com.bpms.connectors.creditconveyer.connector;

import com.bpms.connectors.creditconveyer.connector.support.Io;
import com.bpms.connectors.creditconveyer.dto.LogCreditRequestCreateDTO;
import com.bpms.connectors.creditconveyer.service.BiSetStateService;
import com.bpms.spi.connector.Connector;
import com.bpms.spi.connector.ConnectorContext;
import com.bpms.spi.connector.ConnectorDescriptor;
import com.bpms.spi.connector.ConnectorInputDesc;
import com.bpms.spi.connector.ConnectorResult;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * id = eski {@code @Component("BISetStateConnector")}.
 * Soft no-op: always {@code ConnectorResult.ok}; optional BI POST when {@code bi.endpoint} is set.
 */
public final class BISetStateConnector implements Connector {

    private final BiSetStateService biSetStateService;

    public BISetStateConnector(BiSetStateService biSetStateService) {
        this.biSetStateService = biSetStateService;
    }

    @Override
    public String id() {
        return "BISetStateConnector";
    }

    @Override
    public ConnectorResult execute(ConnectorContext ctx) {
        Locale.setDefault(Locale.forLanguageTag("uz"));
        try {
            if (biSetStateService.isConfigured()) {
                Map<String, Object> in = ctx.inputs();
                Map<String, Object> vars = ctx.variables() == null ? Map.of() : ctx.variables();

                LogCreditRequestCreateDTO dto = new LogCreditRequestCreateDTO();
                dto.setRequestIdAlt(strVar(vars, "request_id_tune_credit_request_start_form"));
                dto.setPinId(strVar(vars, "pinfl_tune_credit_request_start_form"));
                dto.setPassport(strVar(vars, "passport_tune_credit_request_start_form"));
                dto.setState(Io.str(in.get("state")));
                dto.setServiceType(resolveServiceType(vars));
                dto.setData(resolveData(in, vars));

                biSetStateService.setState(dto);
            }
        } catch (Exception ignored) {
            // never fail the token
        }
        return ConnectorResult.ok(Map.of());
    }

    private static Object resolveData(Map<String, Object> in, Map<String, Object> vars) {
        Object data = in.get("data");
        if (data != null) {
            return data;
        }
        Object dataJson = in.get("dataJson");
        if (dataJson != null) {
            String key = Io.str(dataJson);
            if (!key.isBlank() && !key.contains("$")) {
                Object fromVar = vars.get(key);
                if (fromVar != null) {
                    return fromVar;
                }
            }
        }
        return vars.isEmpty() ? null : vars;
    }

    private static String resolveServiceType(Map<String, Object> vars) {
        String serviceType = strVar(vars, "service_type");
        if (serviceType.isBlank() || serviceType.contains("$")) {
            return "CREDIT";
        }
        return serviceType;
    }

    private static String strVar(Map<String, Object> vars, String key) {
        Object v = vars.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    @Override
    public ConnectorDescriptor describe() {
        return new ConnectorDescriptor(id(), "BI set credit-request state (soft no-op)",
                List.of(
                        new ConnectorInputDesc("state", false, "string", "State label"),
                        new ConnectorInputDesc("data", false, "map", "Optional data map"),
                        new ConnectorInputDesc("dataJson", false, "string", "Optional variable name for data")
                ),
                List.of());
    }
}
