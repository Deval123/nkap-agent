package dev.nkap.agent.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nkap.agent.model.NebiusModel;
import dev.nkap.agent.scenario.Catalogue;
import dev.nkap.agent.scenario.ScenarioCase;
import dev.nkap.agent.tools.AgentTools;
import dev.nkap.agent.tools.DirectoryStatementStore;
import dev.nkap.agent.tools.DocsIndex;
import dev.nkap.agent.tools.GatewayClient;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The runner the maintainer uses with the key: each catalogue case K times against the seeded stack, with a
 * table of what the model did against what the catalogue expects. Run through {@code scripts/run-cases.sh}.
 *
 * <p>Safeguards, as for the spike: a hard cap on model calls, a worst-case spend printed before the first
 * call, a stop after three failed calls in a row, the key read from the environment and never printed, and
 * results kept in a directory that git ignores. The model key is {@code NEBIUS_API_KEY}; the gateway key is
 * {@code NKAP_AGENT_KEY}, as for {@code ToolsCli}.
 *
 * <p>The table and the totals go to standard output and nothing else does. The log goes to standard error at
 * WARNING, or at INFO with {@code --verbose}.
 *
 * <p>{@code fake-data DIR} writes what {@code scripts/verify-run.sh} needs to run it with no model.
 */
public final class RunCli {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** The model the runner uses unless --model says otherwise; the results file does not record the model. */
    public static final String DEFAULT_MODEL = "nvidia/nemotron-3-super-120b-a12b";

    private static final int ASSUMED_PROMPT_TOKENS = 8000;

    /**
     * What the task names as the statement import for a case that has none (the seed imports a statement only for
     * a case with lines). The task template is the same for every case, so it still names an import; the gateway
     * answers 404 to it, which the model reads as the tool result it is.
     */
    static final String NO_IMPORT = "00000000-0000-0000-0000-000000000000";

    private RunCli() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.getenv(), System.out, System.err));
    }

    static int run(String[] args, Map<String, String> env, PrintStream out, PrintStream err) {
        if (args.length == 2 && args[0].equals("fake-data")) {
            try {
                FakeData.write(Path.of(args[1]));
                return 0;
            } catch (IOException e) {
                err.println("run: " + e.getClass().getSimpleName());
                return 1;
            }
        }
        if (args.length >= 1 && args[0].equals("run")) {
            return runCases(args, env, out, err);
        }
        err.println("usage: RunCli run --manifest FILE [--runs K] [--max-calls N] [--results-dir DIR] [--statements-dir DIR] [--model ID] [--max-tokens N] [--verbose]"
                + " | RunCli fake-data DIR");
        return 2;
    }

    private static int refuse(PrintStream err, String message) {
        err.println("run: " + message + " Nothing was sent.");
        return 2;
    }

    private static int runCases(String[] args, Map<String, String> env, PrintStream out, PrintStream err) {
        Map<String, String> options = new java.util.HashMap<>();
        boolean verbose = false;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--verbose")) {
                verbose = true;
                continue;
            }
            if (!args[i].startsWith("--") || i + 1 >= args.length) {
                return refuse(err, "an option is --name value.");
            }
            options.put(args[i].substring(2), args[++i]);
        }
        configureLogging(verbose);
        String modelKey = env.get("NEBIUS_API_KEY");
        if (modelKey == null || modelKey.isBlank()) {
            return refuse(err, "NEBIUS_API_KEY is not set.");
        }
        String agentKey = env.get("NKAP_AGENT_KEY");
        if (agentKey == null || agentKey.isBlank()) {
            return refuse(err, "NKAP_AGENT_KEY is not set.");
        }
        String base = env.getOrDefault("NEBIUS_BASE_URL", "https://api.tokenfactory.nebius.com/v1/");
        if (!(base.startsWith("https://") || base.startsWith("http://127.0.0.1:") || base.startsWith("http://localhost:"))) {
            return refuse(err, "NEBIUS_BASE_URL must be https, or a loopback address: the key is not sent in clear.");
        }
        Integer runs = boundedInt(options.getOrDefault("runs", "3"), 1, 5);
        Integer maxCalls = boundedInt(options.getOrDefault("max-calls", "60"), 1, 120);
        Integer maxTokens = boundedInt(options.getOrDefault("max-tokens", "6000"), 1, NebiusModel.MAX_TOKENS_LIMIT);
        if (runs == null) {
            return refuse(err, "--runs is a whole number from 1 to 5.");
        }
        if (maxCalls == null) {
            return refuse(err, "--max-calls is a whole number from 1 to 120.");
        }
        if (maxTokens == null) {
            return refuse(err, "--max-tokens is a whole number from 1 to " + NebiusModel.MAX_TOKENS_LIMIT + ".");
        }
        double priceIn;
        double priceOut;
        try {
            priceIn = Double.parseDouble(env.getOrDefault("RUN_PRICE_IN", "0.30"));
            priceOut = Double.parseDouble(env.getOrDefault("RUN_PRICE_OUT", "0.90"));
        } catch (NumberFormatException e) {
            return refuse(err, "RUN_PRICE_IN and RUN_PRICE_OUT are decimal numbers.");
        }
        JsonNode manifest;
        try {
            if (!options.containsKey("manifest")) {
                return refuse(err, "--manifest FILE is needed.");
            }
            manifest = JSON.readTree(Path.of(options.get("manifest")).toFile());
        } catch (IOException | RuntimeException e) {
            return refuse(err, "the manifest cannot be read.");
        }
        List<String[]> targets = new ArrayList<>();
        for (ScenarioCase c : Catalogue.all()) {
            String ref = null;
            String imp = null;
            boolean found = false;
            for (JsonNode entry : manifest.path("cases")) {
                if (entry.path("id").asText().equals(c.id())) {
                    ref = entry.path("paymentReference").asText(null);
                    imp = entry.path("statementImportId").asText(null);
                    found = entry.has("statementImportId");
                }
            }
            if (imp == null && found) {
                imp = NO_IMPORT;
            }
            if (ref == null || imp == null) {
                return refuse(err, "the manifest has no entry for the case " + c.id() + ".");
            }
            targets.add(new String[] {c.id(), ref, imp});
        }

        Path statements = statementsDir(options, Path.of(options.get("manifest")));
        String gatewayUrl = env.getOrDefault("NKAP_GATEWAY_URL", "http://127.0.0.1:8080");
        String modelId = options.getOrDefault("model", DEFAULT_MODEL);
        Path resultsDir = Path.of(options.getOrDefault("results-dir", "run-results"));

        int planned = Math.min(maxCalls, targets.size() * runs * AgentLoop.Limits.defaults().maxCalls());
        double worst = planned * (ASSUMED_PROMPT_TOKENS * priceIn + maxTokens * priceOut) / 1e6;
        out.printf("run: %d case(s) x %d run(s), hard cap %d model calls (at most %d planned).%n", targets.size(), runs, maxCalls, planned);
        out.printf("run: worst-case spend, if every call used %d prompt tokens and the %d-token bound in full: about $%.4f%n",
                ASSUMED_PROMPT_TOKENS, maxTokens, worst);
        out.printf("run: (%s at $%.2f in / $%.2f out per million tokens; the cap limits it, this is not a prediction)%n",
                modelId, priceIn, priceOut);

        NebiusModel nebius = new NebiusModel(base, NebiusModel.keyFromEnvironment(), modelId, maxTokens, Duration.ofSeconds(60));
        CappedModel model = new CappedModel(nebius, maxCalls, 3);
        AgentLoop loop = new AgentLoop(model, AgentLoop.Limits.defaults());
        GatewayClient gateway = new GatewayClient(gatewayUrl, GatewayClient.agentKeyFromEnvironment());

        List<String> rows = new ArrayList<>();
        long promptTotal = 0;
        long completionTotal = 0;
        int done = 0;
        int matched = 0;
        long calls = 0;
        Path file = null;
        try {
            Files.createDirectories(resultsDir);
            file = resultsDir.resolve("run-" + DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
                    .format(Instant.now()) + ".jsonl");
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (IOException e) {
            return refuse(err, "the results directory cannot be written.");
        }
        out.println();
        out.println(String.format("%-20s %3s  %-12s %-34s %5s %8s %10s %8s %9s", "case", "run", "outcome", "vs catalogue",
                "calls", "prompt", "completion", "ms", "cost USD"));
        outer:
        for (String[] target : targets) {
            ScenarioCase scenario = Catalogue.all().stream().filter(c -> c.id().equals(target[0])).findFirst().orElseThrow();
            for (int k = 1; k <= runs; k++) {
                if (model.stopped()) {
                    break outer;
                }
                RunRecord record = loop.run(trace -> new AgentTools(gateway, DocsIndex.bundled(), new DirectoryStatementStore(statements), trace), target[1], target[2]);
                Judgement judgement = Expectation.judge(scenario, record.outcome());
                double cost = (record.promptTokens() * priceIn + record.completionTokens() * priceOut) / 1e6;
                out.println(String.format("%-20s %3d  %-12s %-34s %5d %8d %10d %8d %9.4f", target[0], k, record.outcome().kind(),
                        (judgement.matches() ? "ok: " : "NO: ") + judgement.text(), record.modelCalls(), record.promptTokens(),
                        record.completionTokens(), record.elapsedMillis(), cost));
                done++;
                matched += judgement.matches() ? 1 : 0;
                calls += record.modelCalls();
                promptTotal += record.promptTokens();
                completionTotal += record.completionTokens();
                try {
                    ObjectNode line = JSON.createObjectNode();
                    line.put("case", target[0]);
                    line.put("run", k);
                    line.put("matches", judgement.matches());
                    line.put("judgement", judgement.text());
                    line.set("record", JSON.readTree(record.toJson()));
                    Files.writeString(file, line + "\n", java.nio.file.StandardOpenOption.APPEND);
                } catch (IOException e) {
                    err.println("run: a record could not be written.");
                }
            }
        }
        double spent = (promptTotal * priceIn + completionTotal * priceOut) / 1e6;
        out.println();
        out.printf("run: %d of %d run(s) matched the catalogue; %d model call(s), %d prompt and %d completion tokens, about $%.4f%n",
                matched, targets.size() * runs, calls, promptTotal, completionTotal, spent);
        out.println("run: records (no key in them): " + file);
        if (model.stopped()) {
            err.println("run: STOPPED, " + model.stopReason() + "; " + done + " of " + targets.size() * runs + " run(s) were made.");
        }
        return matched == targets.size() * runs ? 0 : 1;
    }

    /**
     * The tools and the model log through {@code System.Logger}, which the JDK sends to its logger and so to
     * standard error. The table goes to standard output; the log is kept below it by default and shown at
     * INFO with {@code --verbose}. The classes that log are not touched: only the JDK logger's level is.
     */
    public static void configureLogging(boolean verbose) {
        java.util.logging.Level level = verbose ? java.util.logging.Level.INFO : java.util.logging.Level.WARNING;
        java.util.logging.Logger root = java.util.logging.Logger.getLogger("");
        root.setLevel(level);
        for (java.util.logging.Handler handler : root.getHandlers()) {
            handler.setLevel(level);
        }
    }

    /** {@code --statements-dir}, or the {@code statements} directory beside the manifest, where the seed keeps them. */
    static Path statementsDir(Map<String, String> options, Path manifest) {
        if (options.containsKey("statements-dir")) {
            return Path.of(options.get("statements-dir"));
        }
        Path parent = manifest.toAbsolutePath().getParent();
        return (parent == null ? Path.of(".") : parent).resolve("statements");
    }

    private static Integer boundedInt(String text, int min, int max) {
        try {
            int n = Integer.parseInt(text);
            return n >= min && n <= max ? n : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
