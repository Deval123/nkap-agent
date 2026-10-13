package dev.nkap.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs one tool from the command line and prints what a model would be given: one line of JSON for the
 * result, then one line for each {@code --cite PATH=VALUE} saying whether the claim is accepted. It is how
 * {@code scripts/verify-tools.sh} exercises the tools against a real stack.
 *
 * <pre>
 * NKAP_GATEWAY_URL=http://127.0.0.1:8080 NKAP_AGENT_KEY=... \
 *   ToolsCli get_payment &lt;reference&gt; [--cite /state=UNKNOWN]
 * </pre>
 */
public final class ToolsCli {

    static final List<String> TOOLS = AgentTools.TOOL_NAMES;
    private static final ObjectMapper JSON = new ObjectMapper();

    private ToolsCli() {
    }

    static int run(String[] args, AgentTools tools, PrintStream out) {
        if (args.length < 2 || !TOOLS.contains(args[0])) {
            System.err.println("usage: ToolsCli <" + String.join("|", TOOLS) + "> <argument> [--cite PATH=VALUE]...");
            return 2;
        }
        List<String> cites = new ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            if (!args[i].equals("--cite") || i + 1 >= args.length || !args[i + 1].contains("=")) {
                System.err.println("usage: --cite takes PATH=VALUE");
                return 2;
            }
            cites.add(args[++i]);
        }
        Trace trace = tools.trace();
        ToolResult result = switch (args[0]) {
            case "get_payment" -> tools.getPayment(args[1]);
            case "get_statement_findings" -> tools.getStatementFindings(args[1]);
            case "get_statement_lines" -> tools.getStatementLines(args[1]);
            default -> tools.searchDocs(args[1]);
        };
        out.println(result.toJson());
        for (String cite : cites) {
            int eq = cite.indexOf('=');
            Verification v = trace.verify(new EvidenceRef(result.callId(), cite.substring(0, eq), cite.substring(eq + 1)));
            ObjectNode line = JSON.createObjectNode();
            line.put("cite", cite);
            line.put("ok", v.ok());
            line.put("reason", v.reason());
            out.println(line);
        }
        return 0;
    }

    public static void main(String[] args) {
        String url = System.getenv().getOrDefault("NKAP_GATEWAY_URL", "http://127.0.0.1:8080");
        String statements = System.getenv("NKAP_STATEMENTS_DIR");
        StatementStore store = statements == null || statements.isBlank() ? id -> java.util.Optional.empty()
                : new DirectoryStatementStore(java.nio.file.Path.of(statements));
        AgentTools tools = new AgentTools(new GatewayClient(url, GatewayClient.agentKeyFromEnvironment()),
                DocsIndex.bundled(), store, new Trace());
        System.exit(run(args, tools, System.out));
    }
}
