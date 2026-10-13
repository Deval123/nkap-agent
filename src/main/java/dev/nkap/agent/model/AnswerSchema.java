package dev.nkap.agent.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nkap.agent.scenario.Hypothesis;
import dev.nkap.agent.tools.AgentTools;
import dev.nkap.agent.tools.EvidencePointer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * The one strict JSON schema the model answers with, the same on every turn, and the parser that holds an
 * answer to it.
 *
 * <p>The schema is one object with an {@code action} and nullable fields, not a {@code oneOf} of two
 * objects. Whether the provider's strict mode accepts a {@code oneOf} could not be established without
 * calling it, and a flat object uses only the keywords every strict decoder takes.
 *
 * <p>The spike measured what the provider enforces: the shape (eight fields, valid enums) and not the
 * coupling between them. In a {@code final} answer it wrote {@code argument: ""} and a {@code tool}; in 11
 * of 20 it wrote {@code outcome: null} beside a tool. So {@link #parse} reads the fields the answer's action
 * uses, strictly, and ignores the others whatever they hold: an ignored field is never stored, logged or
 * shown.
 */
public final class AnswerSchema {

    /** The name given to the schema in the request. */
    public static final String NAME = "agent_answer";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private static final ObjectNode SCHEMA = build();

    private AnswerSchema() {
    }

    /** The schema as a tree. A fresh copy: callers cannot change the one the parser relies on. */
    public static ObjectNode schema() {
        return SCHEMA.deepCopy();
    }

    /** The {@code response_format} member of a request: this schema, strict. */
    public static ObjectNode responseFormat() {
        ObjectNode format = F.objectNode();
        format.put("type", "json_schema");
        ObjectNode wrapper = format.putObject("json_schema");
        wrapper.put("name", NAME);
        wrapper.put("strict", true);
        wrapper.set("schema", schema());
        return format;
    }

    private static ObjectNode build() {
        ObjectNode properties = F.objectNode();
        properties.set("action", enumOf(false, "call_tool", "final"));
        properties.set("tool", enumOf(true, AgentTools.TOOL_NAMES.toArray(String[]::new)));
        properties.set("argument", type("string", true));
        properties.set("outcome", enumOf(true, Arrays.stream(ModelAnswer.Verdict.values())
                .map(ModelAnswer.Verdict::code).toArray(String[]::new)));
        properties.set("hypothesis", enumOf(true, Arrays.stream(Hypothesis.values())
                .map(Hypothesis::code).toArray(String[]::new)));

        ObjectNode posting = F.objectNode();
        posting.put("type", "object");
        ObjectNode postingProps = posting.putObject("properties");
        postingProps.set("account", type("string", false));
        postingProps.set("amountMinor", type("integer", false));
        postingProps.set("currency", type("string", false));
        closed(posting);
        ObjectNode postings = type("array", true);
        postings.set("items", posting);
        properties.set("postings", postings);

        ObjectNode reference = F.objectNode();
        reference.put("type", "object");
        ObjectNode referenceProps = reference.putObject("properties");
        referenceProps.set("callId", type("string", false));
        referenceProps.set("path", type("string", false));
        closed(reference);
        ObjectNode evidence = type("array", true);
        evidence.set("items", reference);
        properties.set("evidence", evidence);

        properties.set("explanation", type("string", true));

        ObjectNode root = F.objectNode();
        root.put("type", "object");
        root.set("properties", properties);
        closed(root);
        return root;
    }

    /** Strict mode wants every property required and no other property allowed. */
    private static void closed(ObjectNode object) {
        ArrayNode required = object.putArray("required");
        object.get("properties").fieldNames().forEachRemaining(required::add);
        object.put("additionalProperties", false);
    }

    private static ObjectNode type(String type, boolean nullable) {
        ObjectNode node = F.objectNode();
        if (nullable) {
            node.putArray("type").add(type).add("null");
        } else {
            node.put("type", type);
        }
        return node;
    }

    private static ObjectNode enumOf(boolean nullable, String... values) {
        ObjectNode node = type("string", nullable);
        ArrayNode list = node.putArray("enum");
        Arrays.stream(values).forEach(list::add);
        if (nullable) {
            list.addNull();
        }
        return node;
    }

    /**
     * The answer as the eight-field object, the same on every turn. This is what goes back to the model as
     * its own earlier turn: only the fields the action uses are filled, and what the model wrote in the
     * others is not kept.
     */
    public static String toJson(ModelAnswer answer) {
        ObjectNode node = F.objectNode();
        for (String name : List.of("action", "tool", "argument", "outcome", "hypothesis", "postings", "evidence", "explanation")) {
            node.putNull(name);
        }
        if (answer instanceof ModelAnswer.CallTool call) {
            node.put("action", "call_tool");
            node.put("tool", call.tool());
            node.put("argument", call.argument());
        } else if (answer instanceof ModelAnswer.Final fin) {
            node.put("action", "final");
            node.put("outcome", fin.outcome().code());
            if (fin.hypothesis() != null) {
                node.put("hypothesis", fin.hypothesis().code());
            }
            ArrayNode postings = node.putArray("postings");
            fin.postings().forEach(p -> postings.addObject().put("account", p.account()).put("amountMinor", p.amountMinor())
                    .put("currency", p.currency()));
            ArrayNode evidence = node.putArray("evidence");
            fin.evidence().forEach(e -> evidence.addObject().put("callId", e.callId()).put("path", e.path()));
            node.put("explanation", fin.explanation());
        }
        return node.toString();
    }

    // --- parsing

    private static final Set<String> TOP = Set.of("action", "tool", "argument", "outcome", "hypothesis",
            "postings", "evidence", "explanation");

    /** Holds the text the model produced to the schema, and returns the answer it holds. */
    public static ModelAnswer parse(String content) throws SchemaViolation {
        JsonNode root;
        try {
            root = JSON.readTree(content);
        } catch (JsonProcessingException | RuntimeException e) {
            throw new SchemaViolation("the answer is not JSON");
        }
        if (root == null || !root.isObject()) {
            throw new SchemaViolation("the answer is not a JSON object");
        }
        int unknown = 0;
        for (var names = root.fieldNames(); names.hasNext();) {
            if (!TOP.contains(names.next())) {
                unknown++;
            }
        }
        if (unknown > 0) {
            throw new SchemaViolation("answer has " + unknown + " property outside the schema");
        }
        String action = enumValue(root, "action", List.of("call_tool", "final"), false);
        if (action.equals("call_tool")) {
            return callTool(root);
        }
        return finalAnswer(root);
    }

    private static ModelAnswer callTool(JsonNode root) throws SchemaViolation {
        String tool = enumValue(root, "tool", AgentTools.TOOL_NAMES, false);
        JsonNode argument = root.get("argument");
        if (argument == null || !argument.isTextual() || argument.asText().isBlank()) {
            throw new SchemaViolation("argument must be a non-blank string");
        }
        return new ModelAnswer.CallTool(tool, argument.asText());
    }

    private static ModelAnswer finalAnswer(JsonNode root) throws SchemaViolation {
        String outcomeCode = enumValue(root, "outcome", Arrays.stream(ModelAnswer.Verdict.values())
                .map(ModelAnswer.Verdict::code).toList(), false);
        ModelAnswer.Verdict outcome = Arrays.stream(ModelAnswer.Verdict.values())
                .filter(v -> v.code().equals(outcomeCode)).findFirst().orElseThrow();
        String hypothesisCode = enumValue(root, "hypothesis", Arrays.stream(Hypothesis.values())
                .map(Hypothesis::code).toList(), true);
        Hypothesis hypothesis = hypothesisCode == null ? null : Arrays.stream(Hypothesis.values())
                .filter(h -> h.code().equals(hypothesisCode)).findFirst().orElseThrow();
        List<Posting> postings = postings(root.get("postings"));
        List<EvidencePointer> evidence = evidence(root.get("evidence"));
        JsonNode explanation = root.get("explanation");
        if (explanation == null || !explanation.isTextual() || explanation.asText().isBlank()) {
            throw new SchemaViolation("explanation must be a non-blank string");
        }
        if (outcome == ModelAnswer.Verdict.PROPOSE) {
            if (hypothesis == null) {
                throw new SchemaViolation("hypothesis is required when the outcome is propose");
            }
            if (postings.isEmpty()) {
                throw new SchemaViolation("postings are required when the outcome is propose");
            }
            if (evidence.isEmpty()) {
                throw new SchemaViolation("evidence is required when the outcome is propose");
            }
        } else {
            if (hypothesis != null) {
                throw new SchemaViolation("hypothesis must be null when the outcome is no_proposal");
            }
            if (!postings.isEmpty()) {
                throw new SchemaViolation("postings must be empty when the outcome is no_proposal");
            }
        }
        return new ModelAnswer.Final(outcome, hypothesis, postings, evidence, explanation.asText());
    }

    private static List<Posting> postings(JsonNode node) throws SchemaViolation {
        List<Posting> out = new ArrayList<>();
        if (node == null || node.isNull()) {
            return out;
        }
        if (!node.isArray()) {
            throw new SchemaViolation("postings must be an array");
        }
        for (int i = 0; i < node.size(); i++) {
            String where = "postings[" + i + "]";
            JsonNode item = node.get(i);
            if (!item.isObject()) {
                throw new SchemaViolation(where + " must be an object");
            }
            exactFields(item, Set.of("account", "amountMinor", "currency"), where);
            JsonNode amount = item.get("amountMinor");
            if (!amount.isIntegralNumber() || !amount.canConvertToLong()) {
                throw new SchemaViolation(where + ".amountMinor must be a whole number");
            }
            out.add(new Posting(text(item, "account", where), amount.asLong(), text(item, "currency", where)));
        }
        return out;
    }

    private static List<EvidencePointer> evidence(JsonNode node) throws SchemaViolation {
        List<EvidencePointer> out = new ArrayList<>();
        if (node == null || node.isNull()) {
            return out;
        }
        if (!node.isArray()) {
            throw new SchemaViolation("evidence must be an array");
        }
        for (int i = 0; i < node.size(); i++) {
            String where = "evidence[" + i + "]";
            JsonNode item = node.get(i);
            if (!item.isObject()) {
                throw new SchemaViolation(where + " must be a pointer object, not free text");
            }
            exactFields(item, Set.of("callId", "path"), where);
            out.add(new EvidencePointer(text(item, "callId", where), text(item, "path", where)));
        }
        return out;
    }

    private static String text(JsonNode object, String field, String where) throws SchemaViolation {
        JsonNode node = object.get(field);
        if (!node.isTextual()) {
            throw new SchemaViolation(where + "." + field + " must be a string");
        }
        return node.asText();
    }

    /** The value of an enum field, or null if it is allowed to be and is null. Never repeats a bad value. */
    private static String enumValue(JsonNode object, String field, List<String> allowed, boolean nullable)
            throws SchemaViolation {
        JsonNode node = object.get(field);
        if ((node == null || node.isNull()) && nullable) {
            return null;
        }
        if (node == null || !node.isTextual() || !allowed.contains(node.asText())) {
            throw new SchemaViolation(field + " is not one of the allowed values");
        }
        return node.asText();
    }

    /** The object has exactly these fields. Names an unknown field by position only, never by what it says. */
    private static void exactFields(JsonNode object, Set<String> expected, String where) throws SchemaViolation {
        for (String name : expected) {
            if (!object.has(name)) {
                throw new SchemaViolation(where + " is missing " + name);
            }
        }
        int unknown = 0;
        var names = object.fieldNames();
        while (names.hasNext()) {
            if (!expected.contains(names.next())) {
                unknown++;
            }
        }
        if (unknown > 0) {
            throw new SchemaViolation(where + " has " + unknown + " property outside the schema");
        }
    }
}
