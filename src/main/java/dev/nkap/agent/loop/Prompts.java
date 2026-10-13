package dev.nkap.agent.loop;

import dev.nkap.agent.scenario.AccountKind;
import dev.nkap.agent.scenario.Catalogue;
import dev.nkap.agent.scenario.EntryPattern;
import dev.nkap.agent.scenario.Hypothesis;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The one system prompt and the one task template, the same for every case. The entry of each hypothesis is
 * rendered from the catalogue's patterns, so the prompt and the validator cannot disagree about it. Nothing
 * about a case, its amount or its expected outcome is written here.
 */
public final class Prompts {

    private static final String TASK = "Investigate payment {reference}. The statement import {importId} was loaded today.";

    private Prompts() {
    }

    public static String system() {
        String template;
        try (InputStream in = Prompts.class.getResourceAsStream("/prompts/system.md")) {
            template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException | NullPointerException e) {
            throw new IllegalStateException("the system prompt is missing from the build");
        }
        return template.replace("{{patterns}}", patterns()).strip();
    }

    public static String task(String reference, String importId) {
        return TASK.replace("{reference}", reference).replace("{importId}", importId);
    }

    private static String patterns() {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<Hypothesis, EntryPattern> entry : Catalogue.patterns().entrySet()) {
            out.append("- ").append(entry.getKey().code()).append(": ").append(meaning(entry.getKey())).append(" Entry: ")
                    .append(entry.getValue().lines().stream()
                            .map(l -> l.kind().account("<provider>", "<currency>") + (l.sign() > 0 ? " +X" : " -X"))
                            .collect(Collectors.joining(", ")))
                    .append(".\n");
        }
        return out.toString().strip();
    }

    private static String meaning(Hypothesis hypothesis) {
        return switch (hypothesis) {
            case UNRECORDED_FEE -> "the operator charged a fee that the ledger never recorded.";
            case DUPLICATE_POSTING -> "the same movement was posted twice.";
            default -> "";
        };
    }
}
