package com.bpms.server.connector;

import com.bpms.spi.connector.Connector;
import com.bpms.spi.connector.ConnectorContext;
import com.bpms.spi.connector.ConnectorDescriptor;
import com.bpms.spi.connector.ConnectorInputDesc;
import com.bpms.spi.connector.ConnectorResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generic HTTP GET → JSON connector ({@code id = http-json-get}, plan 34).
 *
 * <p>Inputs are evaluated by the engine before this runs. Camunda-style {@code ${var}} templates often
 * survive as literals (or become {@code null} when SpEL cannot parse them) — this connector interpolates
 * them from process variables and, for the exchange-rate demo, can rebuild the open.er-api.com URL from
 * {@code base}/{@code target} when the URL is still unresolved.
 */
public final class HttpJsonGetConnector implements Connector {

    public static final String ID = "http-json-get";

    private static final Pattern TEMPLATE = Pattern.compile("\\$\\{([^}]+)}");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final ObjectMapper json;
    private final HttpClient http;

    public HttpJsonGetConnector(ObjectMapper json) {
        this(json, HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
    }

    /** Test hook — inject a client (or a client pointed at a stub base). */
    public HttpJsonGetConnector(ObjectMapper json, HttpClient http) {
        this.json = Objects.requireNonNull(json, "json");
        this.http = Objects.requireNonNull(http, "http");
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ConnectorResult execute(ConnectorContext ctx) {
        Map<String, Object> in = ctx.inputs() == null ? Map.of() : ctx.inputs();
        Map<String, Object> vars = ctx.variables() == null ? Map.of() : ctx.variables();

        String url = resolveUrl(in.get("url"), vars);
        if (url == null || url.isBlank()) {
            return ConnectorResult.fail("http-json-get: url is required");
        }
        String resultPath = resolveResultPath(in.get("resultPath"), vars);
        BigDecimal threshold = resolveThreshold(in.get("threshold"), vars);

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .header("Accept", "application/json")
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            String body = response.body() == null ? "" : response.body();

            if (status < 200 || status >= 300) {
                return ConnectorResult.fail("http-json-get: HTTP " + status + " for " + url);
            }

            Object extracted = extract(body, resultPath);
            Map<String, Object> out = new HashMap<>();
            out.put("httpStatus", status);
            out.put("body", body);
            out.put("result", extracted);
            // Demo alias used by exchange_rate_alert.bpmn / DoD wording.
            out.put("rate", extracted);
            if (threshold != null && extracted instanceof Number n) {
                out.put("aboveThreshold", BigDecimal.valueOf(n.doubleValue()).compareTo(threshold) >= 0);
            } else if (threshold != null && extracted != null) {
                try {
                    BigDecimal value = new BigDecimal(extracted.toString().trim());
                    out.put("aboveThreshold", value.compareTo(threshold) >= 0);
                } catch (NumberFormatException ignored) {
                    // non-numeric result — leave aboveThreshold unset
                }
            }
            return ConnectorResult.ok(out);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ConnectorResult.fail("http-json-get: interrupted — " + e.getMessage());
        } catch (Exception e) {
            return ConnectorResult.fail("http-json-get: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    private String resolveUrl(Object raw, Map<String, Object> vars) {
        String url = interpolate(str(raw), vars);
        if (url != null && !url.isBlank() && !url.contains("${")) {
            return url;
        }
        // Hedge (plan 34): rebuild the exchange-rate demo URL from `base`.
        String base = str(vars.getOrDefault("base", "USD"));
        if (base == null || base.isBlank() || base.contains("${")) {
            base = "USD";
        }
        return "https://open.er-api.com/v6/latest/" + base.trim();
    }

    private String resolveResultPath(Object raw, Map<String, Object> vars) {
        String path = interpolate(str(raw), vars);
        if (path != null && !path.isBlank() && !path.contains("${")) {
            return path;
        }
        String target = str(vars.getOrDefault("target", "UZS"));
        if (target == null || target.isBlank() || target.contains("${")) {
            target = "UZS";
        }
        return "rates." + target.trim();
    }

    private static BigDecimal resolveThreshold(Object raw, Map<String, Object> vars) {
        Object value = raw;
        if (value == null || str(value).contains("${")) {
            value = vars.get("threshold");
        }
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal b) {
            return b;
        }
        if (value instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        String s = str(value);
        if (s == null || s.isBlank() || s.contains("${")) {
            return null;
        }
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Object extract(String body, String resultPath) throws Exception {
        if (resultPath == null || resultPath.isBlank()) {
            return body;
        }
        JsonNode node = json.readTree(body);
        for (String part : resultPath.split("\\.")) {
            if (part.isBlank()) {
                continue;
            }
            if (node == null || node.isMissingNode() || node.isNull()) {
                return null;
            }
            node = node.get(part);
        }
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            // Prefer BigDecimal so EAV stores type "double" (plan 16) instead of truncating to long.
            return node.decimalValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isTextual()) {
            return node.textValue();
        }
        return json.writeValueAsString(node);
    }

    static String interpolate(String template, Map<String, Object> vars) {
        if (template == null) {
            return null;
        }
        Matcher m = TEMPLATE.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Object v = vars.get(m.group(1).trim());
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? "" : String.valueOf(v)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    @Override
    public ConnectorDescriptor describe() {
        return new ConnectorDescriptor(
                id(),
                "HTTP GET JSON — extract a scalar via resultPath; optional aboveThreshold vs threshold",
                List.of(
                        new ConnectorInputDesc("url", true, "string", "Full GET URL (supports ${var} templates)"),
                        new ConnectorInputDesc("resultPath", false, "string", "Dot path into JSON body, e.g. rates.UZS"),
                        new ConnectorInputDesc("threshold", false, "number", "If set and result is numeric → aboveThreshold")
                ),
                List.of("httpStatus", "result", "rate", "aboveThreshold", "body"));
    }
}
