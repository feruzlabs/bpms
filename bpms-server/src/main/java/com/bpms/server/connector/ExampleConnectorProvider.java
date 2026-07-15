package com.bpms.server.connector;

import com.bpms.spi.connector.Connector;
import com.bpms.spi.connector.ConnectorContext;
import com.bpms.spi.connector.ConnectorDescriptor;
import com.bpms.spi.connector.ConnectorInputDesc;
import com.bpms.spi.connector.ConnectorProvider;
import com.bpms.spi.connector.ConnectorResult;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Namuna (demo/test) serviceTask connectorlari — FAQAT demo/smoke uchun.
 * Ishlab chiqarish connectorlari o'z domen modulida bo'lishi kerak (yadro/serverga hardcode qilinmaydi).
 * Ro'yxatdan o'tish: {@link ConnectorProvider} (@Component) -> ConnectorRegistry (id bo'yicha; dublikat id -> xato).
 * Qo'llanma: docs/writing-connectors.md
 */
@Component
public class ExampleConnectorProvider implements ConnectorProvider {

    @Override
    public Collection<Connector> connectors() {
        return List.<Connector>of(
                new SumConnector(),
                new CreditScoreMockConnector(),
                new SetVariableConnector(),
                new AlwaysFailConnector()
        );
    }

    /** inputs {a,b} (raqam) -> outputs {sum}. */
    static final class SumConnector implements Connector {
        @Override
        public String id() {
            return "demo-sum";
        }

        @Override
        public ConnectorResult execute(ConnectorContext ctx) {
            BigDecimal a = num(ctx.inputs().get("a"));
            BigDecimal b = num(ctx.inputs().get("b"));
            Map<String, Object> out = new HashMap<>();
            out.put("sum", a.add(b));
            return ConnectorResult.ok(out);
        }

        @Override
        public ConnectorDescriptor describe() {
            return new ConnectorDescriptor(id(), "Ikki raqamni qo'shadi",
                    List.of(new ConnectorInputDesc("a", true, "number", "birinchi son"),
                            new ConnectorInputDesc("b", true, "number", "ikkinchi son")),
                    List.of("sum"));
        }
    }

    /** Soxta kredit-skoring. inputs {amount, monthlyIncome} -> outputs {score(int), approved(bool)}. */
    static final class CreditScoreMockConnector implements Connector {
        @Override
        public String id() {
            return "demo-credit-score";
        }

        @Override
        public ConnectorResult execute(ConnectorContext ctx) {
            BigDecimal amount = num(ctx.inputs().get("amount")).max(BigDecimal.ONE);
            BigDecimal income = num(ctx.inputs().get("monthlyIncome"));
            BigDecimal annual = income.multiply(BigDecimal.valueOf(12));
            int score = annual.signum() <= 0 ? 0 : Math.min(100,
                    annual.multiply(BigDecimal.valueOf(100)).divide(amount, RoundingMode.HALF_UP).intValue());
            Map<String, Object> out = new HashMap<>();
            out.put("score", score);
            out.put("approved", score >= 50);
            return ConnectorResult.ok(out);
        }

        @Override
        public ConnectorDescriptor describe() {
            return new ConnectorDescriptor(id(), "Soxta kredit-skoring (demo)",
                    List.of(new ConnectorInputDesc("amount", true, "number", "so'ralgan summa"),
                            new ConnectorInputDesc("monthlyIncome", true, "number", "oylik daromad")),
                    List.of("score", "approved"));
        }
    }

    /** inputs {name, value} -> outputs {<name>: value}. Qiymat/doimiy yozish. */
    static final class SetVariableConnector implements Connector {
        @Override
        public String id() {
            return "demo-set-var";
        }

        @Override
        public ConnectorResult execute(ConnectorContext ctx) {
            String name = String.valueOf(ctx.inputs().getOrDefault("name", "result"));
            Map<String, Object> out = new HashMap<>();
            out.put(name, ctx.inputs().get("value"));
            return ConnectorResult.ok(out);
        }
    }

    /** Har doim fail — xato oqimini (token FAILED / retry) sinash uchun. */
    static final class AlwaysFailConnector implements Connector {
        @Override
        public String id() {
            return "demo-fail";
        }

        @Override
        public ConnectorResult execute(ConnectorContext ctx) {
            return ConnectorResult.fail("demo-fail: ataylab xato (test uchun)");
        }
    }

    private static BigDecimal num(Object o) {
        if (o == null) {
            return BigDecimal.ZERO;
        }
        if (o instanceof BigDecimal b) {
            return b;
        }
        if (o instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        return new BigDecimal(o.toString().trim());
    }
}
