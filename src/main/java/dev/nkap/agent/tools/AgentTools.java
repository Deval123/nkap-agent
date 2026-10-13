package dev.nkap.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The agent's read-only tools. Each one reads state the gateway already holds, and none causes an outbound
 * call to an operator. Every call is recorded in the {@link Trace}, masked and truncated, and every failure
 * is a result with status {@code 0} or the gateway's status, never an exception thrown at the model.
 *
 * <p>{@code get_balance} is deliberately not here: it would make the gateway ask the operator.
 */
public final class AgentTools {

    /** The names under which the model may ask for a tool; the single list the schema and the CLI both use. */
    public static final java.util.List<String> TOOL_NAMES =
            java.util.List.of("get_payment", "get_statement_findings", "search_docs");

    private static final System.Logger LOG = System.getLogger("dev.nkap.agent.tools");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final GatewayClient gateway;
    private final DocsIndex docs;
    private final Trace trace;

    public AgentTools(GatewayClient gateway, DocsIndex docs, Trace trace) {
        this.gateway = gateway;
        this.docs = docs;
        this.trace = trace;
    }

    Trace trace() {
        return trace;
    }

    /**
     * Searches the local copy of Nkap's provider pages: at most three passages, each with its file, its
     * heading and an excerpt. Offline; it makes no call of any kind.
     */
    public ToolResult searchDocs(String query) {
        Map<String, String> arguments = new LinkedHashMap<>();
        arguments.put("query", query == null ? "" : query);
        if (query == null || query.isBlank()) {
            return done(trace.record("search_docs", arguments, 0, error("invalid-argument", "query must not be blank")));
        }
        ObjectNode fields = JsonNodeFactory.instance.objectNode();
        var results = fields.putArray("results");
        for (DocsIndex.Hit hit : docs.search(query)) {
            ObjectNode node = results.addObject();
            node.put("file", hit.file());
            node.put("heading", hit.heading());
            node.put("excerpt", hit.excerpt());
        }
        return done(trace.record("search_docs", arguments, 200, fields, Redactor.DOC_LIMIT));
    }

    /** {@code GET /payments/{reference}}: state, transition history with cause and operator code, escalation. */
    public ToolResult getPayment(String reference) {
        return fromGateway("get_payment", "reference", reference, "/payments/");
    }

    /** {@code GET /statements/imports/{importId}}: the reconciliation report. Needs an admin key. */
    public ToolResult getStatementFindings(String importId) {
        return fromGateway("get_statement_findings", "importId", importId, "/statements/imports/");
    }

    private ToolResult fromGateway(String tool, String argumentName, String argument, String route) {
        Map<String, String> arguments = new LinkedHashMap<>();
        arguments.put(argumentName, argument == null ? "" : argument);
        if (argument == null || !UUID.matcher(argument).matches()) {
            return done(trace.record(tool, arguments, 0, error("invalid-argument", "The argument must be the bare UUID, with no name and no quotes.")));
        }
        try {
            GatewayClient.Response response = gateway.get(route + argument);
            return done(trace.record(tool, arguments, response.status(), parse(response.body())));
        } catch (GatewayUnavailableException e) {
            return done(trace.record(tool, arguments, 0, error("gateway-unreachable", e.getMessage())));
        } catch (IllegalStateException e) {
            return done(trace.record(tool, arguments, 0, error("agent-key-missing", "no agent key is configured")));
        }
    }

    private ToolResult done(ToolResult result) {
        LOG.log(System.Logger.Level.INFO, "tool call {0} {1} status {2}", result.callId(), result.tool(), result.status());
        return result;
    }

    private static JsonNode parse(String body) {
        try {
            JsonNode node = JSON.readTree(body);
            if (node == null || node.isMissingNode()) {
                return error("not-json", "");
            }
            return node;
        } catch (Exception notJson) {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("error", "not-json");
            node.put("body", body);
            return node;
        }
    }

    private static JsonNode error(String error, String detail) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("error", error);
        node.put("detail", detail);
        return node;
    }
}
