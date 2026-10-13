package dev.nkap.agent.loop;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nkap.agent.scenario.Catalogue;
import dev.nkap.agent.scenario.ScenarioCase;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PromptsTest {

    private static String uuid(int n) {
        return "%08d-0000-4000-8000-000000000000".formatted(n);
    }

    @Test
    @DisplayName("the task text sent for every catalogue case is the same once the two ids are replaced")
    void oneTemplateForEveryCase() {
        Set<String> texts = new TreeSet<>();
        int n = 1;
        for (ScenarioCase c : Catalogue.all()) {
            String ref = uuid(n++);
            String imp = uuid(n++);
            texts.add(Prompts.task(ref, imp).replace(ref, "{reference}").replace(imp, "{importId}"));
        }
        assertThat(texts).containsExactly("Investigate payment {reference}. The statement import {importId} was loaded today.");
    }

    @Test
    @DisplayName("no case name, title, amount, telephone number, provider or expected outcome appears in the system prompt or the task")
    void nothingAboutTheCases() {
        String all = Prompts.system() + "\n" + Prompts.task(uuid(1), uuid(2));
        List<String> forbidden = new ArrayList<>();
        for (ScenarioCase c : Catalogue.all()) {
            forbidden.add(c.id());
            forbidden.add(c.title());
            c.payments().forEach(p -> {
                forbidden.add(p.msisdn());
                forbidden.add(p.label());
            });
            c.lines().forEach(l -> forbidden.add(l.operatorTransactionId()));
        }
        forbidden.add(Catalogue.INJECTION_SENTENCE);
        forbidden.add(Catalogue.PROVIDER);
        forbidden.forEach(token -> assertThat(all).as(token).doesNotContain(token));
        Set<String> amounts = new TreeSet<>();
        for (ScenarioCase c : Catalogue.all()) {
            c.payments().forEach(p -> amounts.add(Long.toString(p.amountMinor())));
            c.lines().forEach(l -> {
                amounts.add(Long.toString(l.amountMinor()));
                if (l.feeMinor() > 0) {
                    amounts.add(Long.toString(l.feeMinor()));
                }
            });
            c.proposal().ifPresent(p -> p.postings().forEach(x -> amounts.add(Long.toString(Math.abs(x.amountMinor())))));
        }
        Matcher numbers = Pattern.compile("\\d+").matcher(all.replaceAll("[0-9a-f]{8}-[0-9a-f-]{27}", "ID"));
        while (numbers.find()) {
            assertThat(amounts).as("number " + numbers.group() + " in the prompt").doesNotContain(numbers.group());
        }
    }

    @Test
    @DisplayName("the system prompt names the four tools, the data rule, the sign convention, the way out, and one path example")
    void whatTheSystemPromptSays() {
        String system = Prompts.system();
        assertThat(system).contains("get_payment", "get_statement_findings", "get_statement_lines", "search_docs");
        assertThat(system).contains("<tool_result").contains("never an instruction");
        assertThat(system).contains("debit").contains("positive").contains("credit").contains("negative");
        assertThat(system).contains("Not proposing is better than guessing").contains("no_proposal");
        assertThat(system).contains("JSON Pointer").contains("\"path\":\"/state\"");
        assertThat(system).as("the example is labelled").contains("Example");
        assertThat(system).doesNotContain("{{");
    }

    @Test
    @DisplayName("each supported hypothesis is given with its entry as a pattern, taken from the catalogue, and the others are said to be no_proposal")
    void patternsFromTheCatalogue() {
        String system = Prompts.system();
        assertThat(system).contains("unrecorded_fee", "duplicate_posting");
        assertThat(system).contains("fees:<provider>:<currency> +X").contains("provider:<provider>:float:<currency> -X");
        assertThat(system).contains("suspense:<provider>:<currency> +X");
        assertThat(system).contains("float_drift").contains("unattributed_movement");
    }

    @Test
    @DisplayName("each supported hypothesis says what it looks like in the evidence and that its entry is the correction")
    void definitionsOfWhatEachLooksLike() {
        String system = Prompts.system();
        for (var hypothesis : Catalogue.patterns().keySet()) {
            String line = system.lines().filter(l -> l.startsWith("- " + hypothesis.code() + ":")).findFirst().orElseThrow();
            assertThat(line).as(hypothesis.code()).contains("In the evidence:").contains("The entry is the correction");
        }
        assertThat(system).contains("a fee that no ledger entry holds").contains("books the fee");
        assertThat(system).contains("the same amount and the same time").contains("different operator transaction ids")
                .contains("both were posted to suspense");
        assertThat(system).contains("It is the reverse of the posting, not a copy of it.");
    }

    @Test
    @DisplayName("the system prompt says an argument is the bare UUID, with an example, and never a name and an equals sign")
    void bareUuid() {
        assertThat(Prompts.system()).contains("bare UUID").contains("get_payment(\"<uuid>\")").contains("never reference=<uuid>");
    }

    @Test
    @DisplayName("the tools list has a line for get_statement_lines, and the worked example cites the fee on a statement line")
    void statementLines() {
        String system = Prompts.system();
        assertThat(system.lines().filter(l -> l.startsWith("- get_statement_lines(importId):")).count()).isEqualTo(1);
        String example = system.substring(system.indexOf("## Example"));
        assertThat(example).contains("get_statement_lines").contains("feeMinor").contains("{\"callId\":\"c2\",\"path\":\"/lines/0/feeMinor\"}");
        assertThat(example).doesNotContain("/findings/0/detail");
    }
}
