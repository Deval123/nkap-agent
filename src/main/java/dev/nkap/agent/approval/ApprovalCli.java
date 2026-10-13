package dev.nkap.agent.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nkap.agent.loop.AgentLoop;
import dev.nkap.agent.loop.Proposal;
import dev.nkap.agent.loop.RunCli;
import dev.nkap.agent.loop.RunOutcome;
import dev.nkap.agent.loop.RunRecord;
import dev.nkap.agent.model.Posting;
import dev.nkap.agent.scenario.Catalogue;
import dev.nkap.agent.scenario.ExpectedProposal;
import dev.nkap.agent.scenario.ScenarioCase;
import dev.nkap.agent.tools.AgentTools;
import dev.nkap.agent.tools.DirectoryStatementStore;
import dev.nkap.agent.tools.DocsIndex;
import dev.nkap.agent.tools.EvidenceRef;
import dev.nkap.agent.tools.GatewayClient;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Runs one catalogue case from a scripted answer file to a decision, against a local seeded stack, with no model
 * and no Nebius: the checked proposal is written to Nkap with the agent's key, and a different key approves it.
 *
 * <ul>
 *   <li>{@code answers --manifest F --case NAME [--amount N]} writes the scripted answers of the case.
 *   <li>{@code run --manifest F --case NAME --answers FILE [--statements-dir D] [--self-approve] [--approve-twice]}
 *       runs the loop on the file, proposes, then approves with the key in {@code NKAP_APPROVER_KEY}, or, with
 *       {@code --self-approve}, with the agent's own key, to show Nkap refuse it; then reads the adjustment back.
 *   <li>{@code try-unchecked --manifest F --case NAME --amount N} proposes an entry of that size without the
 *       validator, to show Nkap's own refusal (over its ceiling, for instance).
 * </ul>
 *
 * <p>The only code in this repository that calls the approver is this command, and only in its {@code approve}
 * step; the loop is never given either client. Output is one JSON object per step. Keys are read from
 * {@code NKAP_AGENT_KEY} and {@code NKAP_APPROVER_KEY} and never printed.
 */
public final class ApprovalCli {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ApprovalCli() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.getenv(), System.out, System.err));
    }

    static int run(String[] args, Map<String, String> env, PrintStream out, PrintStream err) {
        RunCli.configureLogging(false);
        if (args.length < 1 || !List.of("answers", "run", "try-unchecked").contains(args[0])) {
            return usage(err, "the command is answers, run or try-unchecked.");
        }
        Map<String, String> options = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--self-approve") || args[i].equals("--approve-twice")) {
                options.put(args[i].substring(2), "true");
            } else if (args[i].startsWith("--") && i + 1 < args.length) {
                options.put(args[i].substring(2), args[++i]);
            } else {
                return usage(err, "an option is --name value.");
            }
        }
        if (!options.containsKey("manifest") || !options.containsKey("case")) {
            return usage(err, "--manifest FILE and --case NAME are needed.");
        }
        ScenarioCase scenario = Catalogue.all().stream().filter(c -> c.id().equals(options.get("case"))).findFirst().orElse(null);
        if (scenario == null) {
            return usage(err, "no such case.");
        }
        String reference;
        String importId;
        try {
            JsonNode entry = null;
            for (JsonNode n : JSON.readTree(Path.of(options.get("manifest")).toFile()).path("cases")) {
                if (n.path("id").asText().equals(scenario.id())) {
                    entry = n;
                }
            }
            if (entry == null || !entry.path("paymentReference").isTextual()) {
                return usage(err, "the manifest has no entry for that case.");
            }
            reference = entry.get("paymentReference").asText();
            importId = entry.path("statementImportId").isTextual() ? entry.get("statementImportId").asText()
                    : "00000000-0000-0000-0000-000000000000";
        } catch (IOException | RuntimeException e) {
            return usage(err, "the manifest cannot be read.");
        }
        switch (args[0]) {
            case "answers": {
                Long amount = null;
                if (options.containsKey("amount")) {
                    try {
                        amount = Long.parseLong(options.get("amount"));
                    } catch (NumberFormatException e) {
                        return usage(err, "--amount is a whole number.");
                    }
                }
                ScriptedAnswers.forCase(scenario, reference, importId, amount).forEach(out::println);
                return 0;
            }
            case "try-unchecked":
                return tryUnchecked(scenario, reference, options, env, out, err);
            default:
                return runCase(scenario, reference, importId, options, env, out, err);
        }
    }

    private static int usage(PrintStream err, String message) {
        err.println("approval: " + message + " Nothing was sent.");
        return 2;
    }

    private static Supplier<String> key(Map<String, String> env, String name) {
        return () -> env.get(name);
    }

    private static boolean has(Map<String, String> env, String name) {
        return env.get(name) != null && !env.get(name).isBlank();
    }

    private static int runCase(ScenarioCase scenario, String reference, String importId, Map<String, String> options,
            Map<String, String> env, PrintStream out, PrintStream err) {
        boolean self = options.containsKey("self-approve");
        if (!options.containsKey("answers")) {
            return usage(err, "--answers FILE is needed.");
        }
        if (!has(env, "NKAP_AGENT_KEY")) {
            return usage(err, "NKAP_AGENT_KEY is not set.");
        }
        if (!self && !has(env, "NKAP_APPROVER_KEY")) {
            return usage(err, "NKAP_APPROVER_KEY is not set.");
        }
        List<String> answers;
        try {
            answers = Files.readAllLines(Path.of(options.get("answers")));
        } catch (IOException e) {
            return usage(err, "the answers file cannot be read.");
        }
        String base = env.getOrDefault("NKAP_GATEWAY_URL", "http://127.0.0.1:8080");
        Path manifest = Path.of(options.get("manifest")).toAbsolutePath();
        Path statements = options.containsKey("statements-dir") ? Path.of(options.get("statements-dir"))
                : manifest.getParent().resolve("statements");
        GatewayClient gateway = new GatewayClient(base, key(env, "NKAP_AGENT_KEY"));
        RunRecord record = new AgentLoop(new ScriptedAnswers(answers), AgentLoop.Limits.defaults())
                .run(trace -> new AgentTools(gateway, DocsIndex.bundled(), new DirectoryStatementStore(statements), trace),
                        reference, importId);
        out.println(step("loop").put("outcome", record.outcome().kind()));
        if (!(record.outcome() instanceof RunOutcome.Proposed proposed)) {
            return record.outcome() instanceof RunOutcome.NoProposal ? 0 : 1;
        }

        AdjustmentProposer proposer = new AdjustmentProposer(base, key(env, "NKAP_AGENT_KEY"));
        ProposalResult created = proposer.propose(proposed.proposal());
        if (created instanceof ProposalResult.Refused refused) {
            out.println(step("propose").put("result", "refused").put("code", refused.code()).put("text", refused.text()));
            return 1;
        }
        UUID id = ((ProposalResult.Created) created).id();
        out.println(step("propose").put("result", "created").put("id", id.toString()));

        // The one place the approver is used. With --self-approve it is built with the agent's own key.
        AdjustmentApprover approver = new AdjustmentApprover(base, key(env, self ? "NKAP_AGENT_KEY" : "NKAP_APPROVER_KEY"));
        int code = 0;
        code |= decide(out, "approve", self ? "agent" : "approver", approver.approve(id));
        if (options.containsKey("approve-twice")) {
            code |= decide(out, "approve-again", self ? "agent" : "approver", approver.approve(id));
        }
        ReadResult read = proposer.get(id);
        if (read instanceof ReadResult.Found found) {
            ObjectNode node = step("read").put("status", found.view().status());
            if (found.view().ledgerEntryId() != null) {
                node.put("ledgerEntryId", found.view().ledgerEntryId());
            }
            out.println(node);
        } else if (read instanceof ReadResult.Refused refused) {
            out.println(step("read").put("code", refused.code()).put("text", refused.text()));
            code = 1;
        }
        return code;
    }

    private static int decide(PrintStream out, String name, String by, DecisionResult result) {
        ObjectNode node = step(name).put("by", by);
        if (result instanceof DecisionResult.Decided decided) {
            out.println(node.put("result", "decided").put("ledgerEntryId", decided.ledgerEntryId()));
            return 0;
        }
        DecisionResult.Refused refused = (DecisionResult.Refused) result;
        out.println(node.put("result", "refused").put("code", refused.code()).put("text", refused.text()));
        return 1;
    }

    private static int tryUnchecked(ScenarioCase scenario, String reference, Map<String, String> options, Map<String, String> env,
            PrintStream out, PrintStream err) {
        if (!options.containsKey("amount") || !options.get("amount").matches("[1-9][0-9]{0,15}")) {
            return usage(err, "--amount is a positive whole number.");
        }
        if (!has(env, "NKAP_AGENT_KEY")) {
            return usage(err, "NKAP_AGENT_KEY is not set.");
        }
        Optional<ExpectedProposal> expected = scenario.proposal();
        if (expected.isEmpty()) {
            return usage(err, "that case expects no proposal, so it has no entry to enlarge.");
        }
        long amount = Long.parseLong(options.get("amount"));
        List<Posting> postings = expected.get().postings().stream()
                .map(p -> new Posting(p.account(), Long.signum(p.amountMinor()) * amount, Catalogue.CURRENCY)).toList();
        Proposal proposal = new Proposal(reference, expected.get().hypothesis(), postings,
                List.of(new EvidenceRef("c1", "/amountMinorUnits", options.get("amount"))), "An entry sent without the validator.");
        ProposalResult result = new AdjustmentProposer(env.getOrDefault("NKAP_GATEWAY_URL", "http://127.0.0.1:8080"),
                key(env, "NKAP_AGENT_KEY")).propose(proposal);
        if (result instanceof ProposalResult.Created created) {
            out.println(step("propose").put("result", "created").put("id", created.id().toString()));
            return 0;
        }
        ProposalResult.Refused refused = (ProposalResult.Refused) result;
        out.println(step("propose").put("result", "refused").put("code", refused.code()).put("text", refused.text()));
        return 1;
    }

    private static ObjectNode step(String name) {
        return JSON.createObjectNode().put("step", name);
    }
}
