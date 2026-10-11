package org.remus.giteabot.review.grounding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.model.DiffSide;
import org.remus.giteabot.review.ReviewDocument;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.remus.giteabot.review.grounding.DoubleCheckService.*;

final class DoubleCheckEvaluation {
    static final int REPETITIONS = 3;
    private static final String ROOT = "grounding/pr66/";

    private DoubleCheckEvaluation() { }

    static List<Case> cases() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode manifest = mapper.readTree(resource("manifest.json"));
        JsonNode historical = mapper.readTree(resource("cases.json"));
        String head = manifest.path("revisions").get(2).asText();
        String base = manifest.path("base").asText();
        Map<String, String> files = new LinkedHashMap<>();
        for (String sha : List.of(head, base)) {
            for (String name : List.of("viewer.source", "workflow.source")) {
                String path = manifest.path("paths").path(name).asText();
                int start = manifest.path("sourceStartLines").path(sha).path(name).asInt();
                files.put(sha + ":" + path, "\n".repeat(start - 1) + resource(sha + "/" + name));
            }
        }
        files.put(head + ":EvalExamples.java", resource("live-eval.source"));
        files.put(base + ":EvalExamples.java", "");
        RepositoryApiClient repository = mock(RepositoryApiClient.class);
        when(repository.getFileContent(eq("bc3tech"), eq("sessionregistry"), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String key = invocation.getArgument(3) + ":" + invocation.getArgument(2);
                    if (!files.containsKey(key)) throw new IllegalArgumentException("Unknown fixture " + key);
                    return files.get(key);
                });
        PinnedSourceReader headReader = new PinnedSourceReader(repository, "bc3tech", "sessionregistry", head);
        PinnedSourceReader baseReader = new PinnedSourceReader(repository, "bc3tech", "sessionregistry", base);
        Evidence pr66 = new Evidence(headReader, baseReader, resource(head + "/review.diff"));
        Evidence synthetic = new Evidence(headReader, baseReader, resource("live-eval.diff"));
        List<Case> cases = new ArrayList<>();
        for (JsonNode item : mapper.readTree(resource("live-eval-cases.json")).path("cases")) {
            JsonNode data = item.has("findingRef") ? historical.path(item.path("findingRef").asText())
                    : item.path("finding");
            ReviewDocument.Finding finding = new ReviewDocument.Finding(item.path("id").asText(),
                    data.path("body").asText(), data.path("path").asText(),
                    DiffSide.valueOf(data.path("side").asText()), data.path("line").asInt(),
                    data.path("severity").asText(), data.path("category").asText(),
                    data.path("quote").asText(), List.of());
            Evidence evidence = switch (item.path("evidence").asText()) {
                case "pr66" -> pr66;
                case "synthetic" -> synthetic;
                default -> throw new IllegalArgumentException("Unknown evidence fixture");
            };
            QuoteMatch match = new QuoteVerifier().verify(finding, evidence.head(), evidence.effectiveBase());
            if (!match.matched()) throw new IllegalStateException("Ungrounded eval case " + finding.id()
                    + ": " + match.failureReason());
            String dispute = item.path("disputeText").asText("");
            Verdict expected = Verdict.valueOf(item.path("expected").asText());
            if (expected != Verdict.KEEP && expected != Verdict.DROP) {
                throw new IllegalArgumentException("Evaluation labels must be KEEP or DROP");
            }
            cases.add(new Case(new Candidate(finding, match.matchedSpan(), !dispute.isBlank(), false, dispute),
                    evidence, expected));
        }
        return List.copyOf(cases);
    }

    private static String resource(String name) throws IOException {
        return new ClassPathResource(ROOT + name).getContentAsString(StandardCharsets.UTF_8);
    }

    static Metrics metrics(List<Result> results) {
        if (results.isEmpty()) throw new IllegalArgumentException("No evaluation results");
        Map<String, List<Result>> byCase = new LinkedHashMap<>();
        results.forEach(result -> byCase.computeIfAbsent(result.testCase().id(), id -> new ArrayList<>()).add(result));
        for (List<Result> runs : byCase.values()) {
            if (runs.size() != REPETITIONS || runs.stream().map(Result::repetition).distinct().count() != REPETITIONS
                    || runs.stream().anyMatch(run -> run.repetition() < 1 || run.repetition() > REPETITIONS
                    || !run.testCase().equals(runs.getFirst().testCase()))) {
                throw new IllegalArgumentException("Each case requires three distinct repetitions with the same label");
            }
        }
        List<Result> bad = results.stream().filter(r -> !r.testCase().disputed() && r.testCase().expected() == Verdict.DROP).toList();
        List<Result> valid = results.stream().filter(r -> !r.testCase().disputed() && r.testCase().expected() == Verdict.KEEP).toList();
        List<Result> disputed = results.stream().filter(r -> r.testCase().disputed()).toList();
        long agreement = byCase.values().stream()
                .filter(runs -> runs.stream().map(r -> r.outcome().verdict()).distinct().count() == 1).count();
        return new Metrics(correctRate(bad), correctRate(valid), correctRate(disputed),
                (double) agreement / byCase.size(), count(results, Verdict.UNCERTAIN),
                count(results, Verdict.FAILED), count(results, Verdict.SKIPPED));
    }

    private static double correctRate(List<Result> results) {
        if (results.isEmpty()) throw new IllegalArgumentException("Missing evaluation label category");
        return (double) results.stream().filter(r -> r.outcome().verdict() == r.testCase().expected()).count() / results.size();
    }

    private static long count(List<Result> results, Verdict verdict) {
        return results.stream().filter(r -> r.outcome().verdict() == verdict).count();
    }

    static String report(List<Result> results) {
        Metrics metrics = metrics(results);
        StringBuilder text = new StringBuilder("| Case | Repeat | Expected | Actual | Reason |\n|---|---|---|---|---|\n");
        for (Result result : results) {
            text.append("| ").append(result.testCase().id()).append(" | ").append(result.repetition())
                    .append(" | ").append(result.testCase().expected()).append(" | ").append(result.outcome().verdict())
                    .append(" | ").append(result.outcome().reason().replace("|", "/").replaceAll("\\R", " "))
                    .append(" |\n");
        }
        text.append(String.format(Locale.ROOT, "\n| Metric | Result | Threshold |\n|---|---|---|\n"
                        + "| False-finding / intended-fix rejection | %.2f%% | >=90%% |\n"
                        + "| Valid-finding retention | %.2f%% | >=95%% |\n"
                        + "| Dispute accuracy | %.2f%% | informational |\n"
                        + "| Run-to-run unanimous agreement | %.2f%% | informational |\n"
                        + "| Uncertain / failed / skipped | %d / %d / %d | not successes |\n",
                100 * metrics.rejection(), 100 * metrics.retention(), 100 * metrics.disputeAccuracy(),
                100 * metrics.agreement(), metrics.uncertain(), metrics.failed(), metrics.skipped()));
        if (!metrics.accepted()) {
            text.append("\nThresholds missed. Configure a separate doubleCheckAiIntegrationId "
                    + "(DOUBLE_CHECK_AI_INTEGRATION_ID) on the bot and re-run this evaluation against that integration.\n");
        }
        return text.toString();
    }

    record Case(Candidate candidate, Evidence evidence, Verdict expected) {
        String id() { return candidate.finding().id(); }
        boolean disputed() { return !candidate.disputeText().isBlank(); }
    }

    record Result(Case testCase, int repetition, DoubleCheckOutcome outcome) { }

    record Metrics(double rejection, double retention, double disputeAccuracy, double agreement,
                   long uncertain, long failed, long skipped) {
        boolean accepted() { return rejection >= 0.90 && retention >= 0.95; }
    }
}
