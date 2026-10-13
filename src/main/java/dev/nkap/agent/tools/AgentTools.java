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
 * <p>Three tools read the gateway or the local documentation; the fourth, {@code get_statement_lines}, reads a file
 * the operator uploaded, from a store of the agent's own. None causes a call to an operator.
 *
 * <p>{@code get_balance} is deliberately not here: it would make the gateway ask the operator.
 */
public final class AgentTools {

    /** The names under which the model may ask for a tool; the single list the schema and the CLI both use. */
    public static final java.util.List<String> TOOL_NAMES =
            java.util.List.of("get_payment", "get_statement_findings", "get_statement_lines", "search_docs");

    private static final System.Logger LOG = System.getLogger("dev.nkap.agent.tools");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BARE_UUID = "The argument must be the bare UUID, with no name and no quotes.";
    private static final Pattern UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final GatewayClient gateway;
    private final DocsIndex docs;
    private final StatementStore statements;
    private final Trace trace;

    /** Tools with no statement store: {@code get_statement_lines} finds nothing. */
    public AgentTools(GatewayClient gateway, DocsIndex docs, Trace trace) {
        this(gateway, docs, id -> java.util.Optional.empty(), trace);
    }

    public AgentTools(GatewayClient gateway, DocsIndex docs, StatementStore statements, Trace trace) {
        this.gateway = gateway;
        this.docs = docs;
        this.statements = statements;
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

    /**
     * The lines of the statement file the operator uploaded for this import, read from the agent's own store, not
     * from the gateway: no network, no write. The id is a UUID and maps to a file the service stored itself.
     */
    public ToolResult getStatementLines(String importId) {
        Map<String, String> arguments = new LinkedHashMap<>();
        arguments.put("importId", importId == null ? "" : importId);
        if (importId == null || !UUID.matcher(importId).matches()) {
            return done(trace.record("get_statement_lines", arguments, 0, error("invalid-argument", BARE_UUID)));
        }
        java.util.UUID id = java.util.UUID.fromString(importId);
        try {
            java.util.Optional<String> text = statements.find(id);
            if (text.isEmpty()) {
                return done(trace.record("get_statement_lines", arguments, 404, error("not-found", "No statement file is held for this import.")));
            }
            java.util.Optional<ObjectNode> parsed = StatementLinesParser.parse(text.get());
            if (parsed.isEmpty()) {
                return done(trace.record("get_statement_lines", arguments, 0, error("not-a-statement", "The file does not start with the statement header.")));
            }
            ObjectNode fields = JsonNodeFactory.instance.objectNode();
            fields.put("importId", id.toString());
            fields.setAll(parsed.get());
            return done(trace.record("get_statement_lines", arguments, 200, fields));
        } catch (StatementRefusedException e) {
            return done(trace.record("get_statement_lines", arguments, 0, error("statement-refused", e.getMessage())));
        }
    }

    private ToolResult fromGateway(String tool, String argumentName, String argument, String route) {
        Map<String, String> arguments = new LinkedHashMap<>();
        arguments.put(argumentName, argument == null ? "" : argument);
        if (argument == null || !UUID.matcher(argument).matches()) {
            return done(trace.record(tool, arguments, 0, error("invalid-argument", BARE_UUID)));
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
