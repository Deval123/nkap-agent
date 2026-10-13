package dev.nkap.agent.trace;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nkap.agent.loop.RunCli;
import dev.nkap.agent.scenario.Catalogue;
import dev.nkap.agent.scenario.ScenarioCase;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a run's record into a published trace: the steps in the order they happened, with what the model said, what
 * each tool returned (already masked and truncated, as the model saw it), the repair message if there was one, and
 * the outcome, plus the provenance that makes it honest: the date, the model, run n of K for that case, tokens,
 * cost, and the catalogue's expectation with whether the run matched.
 *
 * <p>Nothing else is kept. The system prompt, the per-call message counts, the environment, any URL of a host the
 * run used and any key are not in the record to begin with, and the export copies only the fields named here, so
 * nothing else can come along. The file is pretty JSON, LF line endings, in a fixed key order.
 *
 * <p>Command: {@code TraceExport from FILE --case NAME --run N --out DIR [--model ID] [--date YYYY-MM-DD]}.
 */
public final class TraceExport {

    public static final String FORMAT = "nkap-agent-trace/1";
    /** The runner's default prices in USD per million tokens, which are what the cost is computed with. */
    public static final double DEFAULT_INPUT_PRICE = 0.30;
    public static final double DEFAULT_OUTPUT_PRICE = 0.90;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern CALL = Pattern.compile("^<tool_result call=\"(c\\d+)\"");

    /** What a record does not say about itself. {@code matched} and {@code judgement} are the runner's own. */
    public record Provenance(String date, String model, int run, int runs, boolean matched, String judgement,
            double inputPricePerMillion, double outputPricePerMillion) {
    }

    private TraceExport() {
    }

    public static ObjectNode export(JsonNode record, ScenarioCase scenario, Provenance provenance) {
        ObjectNode trace = JSON.createObjectNode();
        trace.put("format", FORMAT);
        trace.put("recording", true);
        trace.set("provenance", provenanceOf(record, scenario, provenance));
        trace.set("steps", steps(record));
        return trace;
    }

    private static ObjectNode provenanceOf(JsonNode record, ScenarioCase scenario, Provenance p) {
        long prompt = record.path("promptTokens").asLong();
        long completion = record.path("completionTokens").asLong();
        ObjectNode node = JSON.createObjectNode();
        node.put("date", p.date());
        node.put("model", p.model());
        node.put("case", scenario.id());
        node.put("title", scenario.title());
        node.put("run", p.run());
        node.put("runs", p.runs());
        node.put("modelCalls", record.path("modelCalls").asInt());
        node.put("promptTokens", prompt);
        node.put("completionTokens", completion);
        node.put("costUsd", BigDecimal.valueOf((prompt * p.inputPricePerMillion() + completion * p.outputPricePerMillion()) / 1e6)
                .setScale(6, RoundingMode.HALF_UP));
        ObjectNode prices = node.putObject("prices");
        prices.put("inputPerMillionUsd", p.inputPricePerMillion());
        prices.put("outputPerMillionUsd", p.outputPricePerMillion());
        node.put("elapsedMillis", record.path("elapsedMillis").asLong());
        ObjectNode expected = node.putObject("expected");
        expected.put("outcome", scenario.outcome().name());
        scenario.proposal().ifPresent(proposal -> {
            expected.put("hypothesis", proposal.hypothesis().code());
            ArrayNode postings = expected.putArray("postings");
            proposal.postings().forEach(x -> postings.addObject().put("account", x.account()).put("amountMinor", x.amountMinor())
                    .put("currency", Catalogue.CURRENCY));
        });
        node.put("matched", p.matched());
        node.put("judgement", p.judgement());
        return node;
    }

    private static ArrayNode steps(JsonNode record) {
        ArrayNode steps = JSON.createArrayNode();
        JsonNode messages = record.path("messages");
        JsonNode calls = record.path("calls");
        JsonNode toolCalls = record.path("toolCalls");
        if (messages.size() >= 2) {
            steps.addObject().put("type", "task").put("text", messages.get(1).path("content").asText());
        }
        int call = 0;
        for (int i = 2; i < messages.size(); i++) {
            JsonNode message = messages.get(i);
            String content = message.path("content").asText();
            switch (message.path("role").asText()) {
                case "ASSISTANT" -> steps.add(modelStep(calls.get(call++)));
                case "TOOL_RESULT" -> {
                    Matcher m = CALL.matcher(content);
                    steps.add(toolStep(m.find() ? find(toolCalls, m.group(1)) : null));
                }
                default -> steps.addObject().put("type", "harness").put("text", content);
            }
        }
        while (call < calls.size()) {
            steps.add(modelStep(calls.get(call++)));
        }
        ObjectNode outcome = JSON.createObjectNode();
        outcome.put("type", "outcome");
        record.path("outcome").fields().forEachRemaining(e -> outcome.set(e.getKey(), e.getValue()));
        steps.add(outcome);
        return steps;
    }

    private static JsonNode find(JsonNode toolCalls, String callId) {
        for (JsonNode toolCall : toolCalls) {
            if (toolCall.path("callId").asText().equals(callId)) {
                return toolCall;
            }
        }
        throw new IllegalArgumentException("the record has no tool call " + callId);
    }

    private static ObjectNode modelStep(JsonNode call) {
        ObjectNode step = JSON.createObjectNode();
        step.put("type", "model");
        step.put("callIndex", call.path("index").asInt());
        step.put("millis", call.path("millis").asLong());
        step.set("promptTokens", call.path("promptTokens"));
        step.set("completionTokens", call.path("completionTokens"));
        if (call.has("answer")) {
            try {
                step.set("answer", JSON.readTree(call.get("answer").asText()));
            } catch (IOException e) {
                throw new IllegalArgumentException("a recorded answer is not JSON");
            }
        } else {
            step.set("failure", call.path("failure"));
        }
        return step;
    }

    private static ObjectNode toolStep(JsonNode toolCall) {
        if (toolCall == null) {
            throw new IllegalArgumentException("a tool result in the record names no call");
        }
        ObjectNode step = JSON.createObjectNode();
        step.put("type", "tool");
        step.set("callId", toolCall.path("callId"));
        step.set("tool", toolCall.path("tool"));
        step.set("arguments", toolCall.path("arguments"));
        step.set("status", toolCall.path("status"));
        step.set("fields", toolCall.path("fields"));
        return step;
    }

    /** Pretty JSON, two-space indent, LF line endings, a trailing newline. */
    public static String render(JsonNode trace) {
        DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter()
                .withSeparators(com.fasterxml.jackson.core.util.Separators.createDefaultInstance()
                        .withObjectFieldValueSpacing(com.fasterxml.jackson.core.util.Separators.Spacing.AFTER))
                .withObjectIndenter(indenter).withArrayIndenter(indenter);
        try {
            return JSON.writer(printer).writeValueAsString(trace) + "\n";
        } catch (IOException e) {
            throw new IllegalStateException("a trace could not be written");
        }
    }

    /**
     * Writes {@code <case>-<run>.json} for a run of a results file written by the runner. K is the number of runs of
     * that case in the file. A run or a case that is not in the file is refused, and nothing is written.
     */
    public static Path fromResults(Path results, String caseId, int run, Path outDir, String model, String date) throws IOException {
        ScenarioCase scenario = Catalogue.all().stream().filter(c -> c.id().equals(caseId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no such case: " + caseId));
        JsonNode chosen = null;
        int runs = 0;
        for (String line : Files.readAllLines(results, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode node = JSON.readTree(line);
            if (node.path("case").asText().equals(caseId)) {
                runs++;
                if (node.path("run").asInt() == run) {
                    chosen = node;
                }
            }
        }
        if (chosen == null) {
            throw new IllegalArgumentException("no run " + run + " of " + caseId + " in " + results.getFileName());
        }
        Provenance provenance = new Provenance(date, model, run, runs, chosen.path("matches").asBoolean(),
                chosen.path("judgement").asText(), DEFAULT_INPUT_PRICE, DEFAULT_OUTPUT_PRICE);
        ObjectNode trace = export(chosen.path("record"), scenario, provenance);
        Files.createDirectories(outDir);
        Path file = outDir.resolve(caseId + "-" + run + ".json");
        Files.writeString(file, render(trace), StandardCharsets.UTF_8);
        return file;
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2 || !args[0].equals("from")) {
            System.err.println("usage: TraceExport from FILE --case NAME --run N --out DIR [--model ID] [--date YYYY-MM-DD]");
            System.exit(2);
        }
        Path file = Path.of(args[1]);
        java.util.Map<String, String> options = new java.util.HashMap<>();
        for (int i = 2; i + 1 < args.length; i += 2) {
            options.put(args[i], args[i + 1]);
        }
        Matcher stamp = Pattern.compile("run-(\\d{4})(\\d{2})(\\d{2})T").matcher(file.getFileName().toString());
        String date = options.getOrDefault("--date", stamp.find() ? stamp.group(1) + "-" + stamp.group(2) + "-" + stamp.group(3) : null);
        if (!options.containsKey("--case") || !options.containsKey("--run") || !options.containsKey("--out") || date == null
                || !options.get("--run").matches("[1-9][0-9]*")) {
            System.err.println("usage: TraceExport from FILE --case NAME --run N --out DIR [--model ID] [--date YYYY-MM-DD]");
            System.exit(2);
        }
        try {
            Path written = fromResults(file, options.get("--case"), Integer.parseInt(options.get("--run")), Path.of(options.get("--out")),
                    options.getOrDefault("--model", RunCli.DEFAULT_MODEL), date);
            System.out.println(written);
        } catch (IllegalArgumentException e) {
            System.err.println("trace: " + e.getMessage());
            System.exit(1);
        }
    }

    /** For tests and the page: the cases a trace can be about. */
    static List<String> caseIds() {
        return Catalogue.all().stream().map(ScenarioCase::id).toList();
    }
}
