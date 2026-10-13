package dev.nkap.agent.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nkap.agent.scenario.Hypothesis;
import dev.nkap.agent.tools.AgentTools;
import dev.nkap.agent.tools.EvidencePointer;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AnswerSchemaTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String UUID = "0985e8df-93aa-4949-b7aa-185e19ea41d0";

    private static List<String> names(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    private static List<String> fieldNames(JsonNode object) {
        List<String> out = new ArrayList<>();
        object.fieldNames().forEachRemaining(out::add);
        return out;
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.isNull() ? null : n.asText()));
        return out;
    }

    private static ModelAnswer parse(String json) throws SchemaViolation {
        return AnswerSchema.parse(json);
    }

    private static String violation(String json) {
        return assertThatThrownBy(() -> AnswerSchema.parse(json)).isInstanceOf(SchemaViolation.class)
                .actual().getMessage();
    }

    // --- the schema and the records stay in step

    @Test
    @DisplayName("every field of both answer records, and of a posting and an evidence reference, is in the schema")
    void everyRecordFieldIsInTheSchema() {
        JsonNode props = AnswerSchema.schema().get("properties");
        List<String> expected = new ArrayList<>(List.of("action"));
        expected.addAll(names(ModelAnswer.CallTool.class));
        expected.addAll(names(ModelAnswer.Final.class));
        assertThat(fieldNames(props)).containsExactlyInAnyOrderElementsOf(expected);

        assertThat(fieldNames(props.get("postings").get("items").get("properties")))
                .containsExactlyInAnyOrderElementsOf(names(Posting.class));
        assertThat(fieldNames(props.get("evidence").get("items").get("properties")))
                .containsExactlyInAnyOrderElementsOf(names(EvidencePointer.class));
    }

    @Test
    @DisplayName("strict mode needs every property required and no extra property allowed, at every level")
    void strictShapeAtEveryLevel() {
        JsonNode schema = AnswerSchema.schema();
        List<JsonNode> objects = new ArrayList<>();
        collectObjects(schema, objects);
        assertThat(objects).hasSize(3);
        for (JsonNode object : objects) {
            assertThat(object.get("additionalProperties").asBoolean(true)).isFalse();
            assertThat(strings(object.get("required"))).containsExactlyInAnyOrderElementsOf(fieldNames(object.get("properties")));
        }
    }

    private static void collectObjects(JsonNode node, List<JsonNode> out) {
        if (node.isObject() && node.has("properties")) {
            out.add(node);
            node.get("properties").forEach(p -> collectObjects(p, out));
        }
        if (node.isObject() && node.has("items")) {
            collectObjects(node.get("items"), out);
        }
    }

    @Test
    @DisplayName("the schema uses no oneOf, anyOf or allOf, and only the keywords every strict decoder is known to take")
    void conservativeKeywordsOnly() {
        Set<String> allowed = Set.of("type", "enum", "properties", "required", "additionalProperties", "items");
        Set<String> used = new HashSet<>();
        keywords(AnswerSchema.schema(), used);
        assertThat(used).isSubsetOf(allowed);
    }

    private static void keywords(JsonNode node, Set<String> used) {
        if (!node.isObject()) {
            return;
        }
        Iterator<String> it = node.fieldNames();
        while (it.hasNext()) {
            String name = it.next();
            JsonNode child = node.get(name);
            if (name.equals("properties")) {
                child.forEach(p -> keywords(p, used));
            } else {
                used.add(name);
                keywords(child, used);
            }
        }
    }

    @Test
    @DisplayName("only action is not nullable; the others are nullable so one object serves both actions")
    void nullableFields() {
        JsonNode props = AnswerSchema.schema().get("properties");
        assertThat(props.get("action").get("type").asText()).isEqualTo("string");
        for (String name : fieldNames(props)) {
            if (!name.equals("action")) {
                assertThat(strings(props.get(name).get("type"))).as(name).contains("null");
            }
        }
    }

    @Test
    @DisplayName("the enums are the tool names, the four hypotheses and the two outcomes, taken from their own lists")
    void enumsComeFromTheirOwnLists() {
        JsonNode props = AnswerSchema.schema().get("properties");
        assertThat(strings(props.get("action").get("enum"))).containsExactly("call_tool", "final");
        assertThat(strings(props.get("tool").get("enum"))).containsExactlyElementsOf(
                withNull(AgentTools.TOOL_NAMES));
        assertThat(strings(props.get("hypothesis").get("enum"))).containsExactlyElementsOf(
                withNull(Arrays.stream(Hypothesis.values()).map(Hypothesis::code).toList()));
        assertThat(strings(props.get("outcome").get("enum"))).containsExactly("propose", "no_proposal", null);
    }

    private static List<String> withNull(List<String> values) {
        List<String> out = new ArrayList<>(values);
        out.add(null);
        return out;
    }

    // --- the answers that conform

    @Test
    @DisplayName("a call_tool answer names one of the three tools and an argument")
    void callTool() throws Exception {
        ModelAnswer answer = parse(callTool("get_payment", UUID));
        assertThat(answer).isEqualTo(new ModelAnswer.CallTool("get_payment", UUID));
    }

    @Test
    @DisplayName("a final answer that proposes carries a hypothesis, postings and evidence references")
    void finalPropose() throws Exception {
        ModelAnswer answer = parse(proposal());
        assertThat(answer).isEqualTo(new ModelAnswer.Final(ModelAnswer.Verdict.PROPOSE, Hypothesis.UNRECORDED_FEE,
                List.of(new Posting("fees:mtn-cm:XAF", 75, "XAF"), new Posting("provider:mtn-cm:float:XAF", -75, "XAF")),
                List.of(new EvidencePointer("c1", "/history/0/operatorCode")), "A fee of 75 was not recorded."));
    }

    @Test
    @DisplayName("a final answer with no proposal has no hypothesis and no postings, and may still cite evidence")
    void finalNoProposal() throws Exception {
        ModelAnswer answer = parse(noProposal("null", "[]"));
        assertThat(answer).isInstanceOf(ModelAnswer.Final.class);
        ModelAnswer.Final fin = (ModelAnswer.Final) answer;
        assertThat(fin.outcome()).isEqualTo(ModelAnswer.Verdict.NO_PROPOSAL);
        assertThat(fin.hypothesis()).isNull();
        assertThat(fin.postings()).isEmpty();
    }

    // --- the answers that do not

    @Test
    @DisplayName("an evidence entry written as free text is a schema failure: evidence is references only")
    void freeTextEvidenceIsRefused() {
        String json = proposal().replaceFirst("\"evidence\":\\[[^]]*]", "\"evidence\":[\"the fee was 75\"]");
        assertThat(violation(json)).contains("evidence[0]");
    }

    @Test
    @DisplayName("an evidence pointer is a call id and a path and nothing else: a value the model adds is refused, a missing path too")
    void evidenceFieldsAreExact() {
        assertThat(violation(proposal().replace("\"path\":\"/history/0/operatorCode\"",
                "\"path\":\"/history/0/operatorCode\",\"value\":\"x\""))).contains("evidence[0]");
        assertThat(violation(proposal().replace("\"callId\":\"c1\",", ""))).contains("evidence[0]");
        assertThat(violation(proposal().replace("\"path\":\"/history/0/operatorCode\"", "\"path\":null")))
                .contains("evidence[0].path");
    }

    @Test
    @DisplayName("an unknown tool name is refused")
    void unknownTool() {
        assertThat(violation(callTool("get_balance", UUID))).contains("tool");
    }

    @Test
    @DisplayName("a call_tool answer needs a non-blank argument")
    void callToolShape() {
        assertThat(violation(callTool("get_payment", null))).contains("argument");
        assertThat(violation(callTool("get_payment", " "))).contains("argument");
    }

    @Test
    @DisplayName("the shapes the provider really produced are accepted: a final with argument \"\" and a tool, a final with outcome and a tool")
    void measuredShapes() throws Exception {
        String withArgument = proposal().replace("\"tool\":null,\"argument\":null", "\"tool\":\"search_docs\",\"argument\":\"\"");
        assertThat(parse(withArgument)).isEqualTo(parse(proposal()));
        String withTool = noProposal("null", "[]").replace("\"tool\":null", "\"tool\":\"get_payment\"");
        assertThat(parse(withTool)).isEqualTo(parse(noProposal("null", "[]")));
    }

    @Test
    @DisplayName("a call_tool answer's final-only fields are ignored whatever they hold, and are not kept")
    void callToolIgnoresFinalFields() throws Exception {
        String junk = callTool("get_payment", UUID).replace("\"outcome\":null", "\"outcome\":\"propose\"")
                .replace("\"hypothesis\":null", "\"hypothesis\":\"nonsense\"")
                .replace("\"postings\":null", "\"postings\":\"free text\"")
                .replace("\"explanation\":null", "\"explanation\":\"SECRET-MODEL-TEXT\"");
        ModelAnswer answer = parse(junk);
        assertThat(answer).isEqualTo(new ModelAnswer.CallTool("get_payment", UUID));
        assertThat(answer.toString()).doesNotContain("SECRET-MODEL-TEXT");
    }

    @Test
    @DisplayName("a final answer with outcome null is refused, even beside a tool: it says nothing")
    void finalNeedsAnOutcome() {
        String json = proposal().replace("\"outcome\":\"propose\"", "\"outcome\":null")
                .replace("\"tool\":null", "\"tool\":\"search_docs\"");
        assertThat(violation(json)).contains("outcome");
    }

    @Test
    @DisplayName("a proposal needs a hypothesis, postings, evidence and an explanation")
    void proposalNeedsEverything() {
        assertThat(violation(proposal().replace("\"unrecorded_fee\"", "null"))).contains("hypothesis");
        assertThat(violation(proposal().replaceFirst("\"postings\":\\[.*?}]", "\"postings\":[]"))).contains("postings");
        assertThat(violation(proposal().replaceFirst("\"evidence\":\\[[^]]*]", "\"evidence\":[]"))).contains("evidence");
        assertThat(violation(proposal().replace("\"A fee of 75 was not recorded.\"", "\"\""))).contains("explanation");
    }

    @Test
    @DisplayName("an unknown hypothesis, an unknown outcome and an unknown action are refused")
    void unknownEnumValues() {
        assertThat(violation(proposal().replace("unrecorded_fee", "rounding"))).contains("hypothesis");
        assertThat(violation(proposal().replace("\"propose\"", "\"approve\""))).contains("outcome");
        assertThat(violation(proposal().replace("\"final\"", "\"approve\""))).contains("action");
    }

    @Test
    @DisplayName("a no_proposal answer that carries a hypothesis or postings is refused")
    void noProposalCarriesNothingToPropose() {
        assertThat(violation(noProposal("\"float_drift\"", "[]"))).contains("hypothesis");
        assertThat(violation(noProposal("null", "[{\"account\":\"a\",\"amountMinor\":1,\"currency\":\"XAF\"}]")))
                .contains("postings");
    }

    @Test
    @DisplayName("a posting amount must be a whole number, not text and not a fraction")
    void amountIsAnInteger() {
        assertThat(violation(proposal().replace("\"amountMinor\":75", "\"amountMinor\":\"75\""))).contains("amountMinor");
        assertThat(violation(proposal().replace("\"amountMinor\":75", "\"amountMinor\":75.5"))).contains("amountMinor");
    }

    @Test
    @DisplayName("a property outside the schema, a missing property, and anything but an object are refused")
    void shapeOfTheObject() {
        assertThat(violation(callTool("get_payment", UUID).replace("{", "{\"thinking\":\"...\","))).contains("outside the schema");
        assertThat(violation("{\"action\":\"call_tool\"}")).contains("tool");
        assertThat(violation("[]")).contains("object");
        assertThat(violation("\"final\"")).contains("object");
        assertThat(violation("not json")).contains("not JSON");
    }

    @Test
    @DisplayName("a violation names the field but never repeats the model's text")
    void violationsDoNotEchoTheModel() {
        String text = "SECRET-MODEL-TEXT-1234567890";
        assertThat(violation(proposal().replace("unrecorded_fee", text))).doesNotContain(text);
        assertThat(violation(callTool(text, UUID))).doesNotContain(text);
        assertThat(violation(callTool("get_payment", UUID).replace("{", "{\"" + text + "\":1,"))).doesNotContain(text);
    }

    // --- fixtures

    private static String callTool(String tool, String argument) {
        return "{\"action\":\"call_tool\",\"tool\":" + quote(tool) + ",\"argument\":" + quote(argument)
                + ",\"outcome\":null,\"hypothesis\":null,\"postings\":null,\"evidence\":null,\"explanation\":null}";
    }

    private static String proposal() {
        return "{\"action\":\"final\",\"tool\":null,\"argument\":null,\"outcome\":\"propose\","
                + "\"hypothesis\":\"unrecorded_fee\",\"postings\":["
                + "{\"account\":\"fees:mtn-cm:XAF\",\"amountMinor\":75,\"currency\":\"XAF\"},"
                + "{\"account\":\"provider:mtn-cm:float:XAF\",\"amountMinor\":-75,\"currency\":\"XAF\"}],"
                + "\"evidence\":[{\"callId\":\"c1\",\"path\":\"/history/0/operatorCode\"}],"
                + "\"explanation\":\"A fee of 75 was not recorded.\"}";
    }

    private static String noProposal(String hypothesis, String postings) {
        return "{\"action\":\"final\",\"tool\":null,\"argument\":null,\"outcome\":\"no_proposal\","
                + "\"hypothesis\":" + hypothesis + ",\"postings\":" + postings + ","
                + "\"evidence\":[{\"callId\":\"c1\",\"path\":\"/state\"}],"
                + "\"explanation\":\"Nothing to adjust.\"}";
    }

    private static String quote(String s) {
        return s == null ? "null" : JSON.valueToTree(s).toString();
    }

    @Test
    @DisplayName("toJson writes the eight-field object, and what it writes parses back to the same answer")
    void roundTrip() throws Exception {
        for (String json : List.of(callTool("get_payment", UUID), proposal(), noProposal("null", "[]"))) {
            ModelAnswer answer = parse(json);
            String written = AnswerSchema.toJson(answer);
            assertThat(fieldNames(JSON.readTree(written))).containsExactlyInAnyOrder("action", "tool", "argument",
                    "outcome", "hypothesis", "postings", "evidence", "explanation");
            assertThat(parse(written)).isEqualTo(answer);
        }
    }
}
