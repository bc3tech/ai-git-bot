package org.remus.giteabot.review.grounding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.admin.AiClientFactory;
import org.remus.giteabot.admin.AiIntegration;
import org.remus.giteabot.admin.AiIntegrationService;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.audit.PrAuditEvent;
import org.remus.giteabot.audit.PrAuditEventService;
import org.remus.giteabot.prworkflow.WorkflowCancelledException;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.model.DiffSide;
import org.remus.giteabot.review.ReviewDocument;
import org.remus.giteabot.review.ReviewFence;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
import static org.remus.giteabot.review.grounding.DoubleCheckService.*;

@ExtendWith(MockitoExtension.class)
class DoubleCheckServiceTest {
    private static final String HEAD = "a".repeat(40);
    private static final String BASE = "b".repeat(40);
    private static final String DIFF = "diff --git a/Main.java b/Main.java\n--- a/Main.java\n+++ b/Main.java\n"
            + "@@ -20,1 +20,1 @@ change\n-oldCall();\n+newCall();\n"
            + "diff --git a/Other.java b/Other.java\n--- a/Other.java\n+++ b/Other.java\n"
            + "@@ -1,1 +1,1 @@ fix\n-broken();\n+fixed();\n";
    @Mock AiIntegrationService integrations;
    @Mock AiClientFactory factory;
    @Mock PrAuditEventService audits;
    @Mock AiClient client;
    @Mock RepositoryApiClient repository;
    private DoubleCheckService service;
    private Evidence evidence;
    private final PrAuditEvent warning = PrAuditEvent.builder().runId(1L).build();

    @BeforeEach
    void setup() {
        service = new DoubleCheckService(integrations, factory, audits);
        evidence = new Evidence(new PinnedSourceReader(repository, "o", "r", HEAD),
                new PinnedSourceReader(repository, "o", "r", BASE), DIFF);
        lenient().when(repository.getFileContent(eq("o"), eq("r"), anyString(), anyString()))
                .thenReturn(IntStream.rangeClosed(1, 40).mapToObj(i -> "codeAtLine" + i + "();")
                        .collect(Collectors.joining("\n")));
    }

    @Test
    void mapsThreeWayVerdictsAndPackagesEvidence() throws Exception {
        when(client.submitReviewPrompt(anyString(), isNull(), anyString())).thenReturn(response(
                verdict("a", "keep"), verdict("b", "drop"), verdict("c", "uncertain")));
        Map<String, DoubleCheckOutcome> result = check(List.of(candidate("a"), candidate("b"), candidate("c")));
        assertThat(result.get("a").verdict()).isEqualTo(Verdict.KEEP);
        assertThat(result.get("b").verdict()).isEqualTo(Verdict.DROP);
        assertThat(result.get("c").held()).isTrue();
        JsonNode request = request();
        assertThat(request.path("changedFiles").toString()).contains("Other.java", "@@ -1,1 +1,1 @@ fix");
        assertThat(request.path("findings").get(0).path("sourceContext").asText()).contains("5: codeAtLine5", "35: codeAtLine35");
        assertThat(request.path("findings").get(0).path("diffHunks").toString()).contains("newCall()");
    }

    @Test
    void disabledStillChecksDisputedAndContextChangedPriors() {
        when(client.submitReviewPrompt(anyString(), isNull(), anyString())).thenReturn(response(verdict("dispute", "keep"), verdict("changed", "drop")));
        Map<String, DoubleCheckOutcome> result = check(List.of(candidate("new"), prior("dispute", false, "not a bug"),
                prior("changed", true, ""), prior("unchanged", false, "")),
                new DoubleCheckSettings(false, DoubleCheckSettings.Severity.HIGH, null), new Budget(100_000, 8));
        assertThat(result.get("new").verdict()).isEqualTo(Verdict.SKIPPED);
        assertThat(result.get("unchanged").verdict()).isEqualTo(Verdict.SKIPPED);
        assertThat(result.get("dispute").verdict()).isEqualTo(Verdict.KEEP);
        assertThat(result.get("changed").verdict()).isEqualTo(Verdict.DROP);
    }

    @Test
    void thresholdSkipsMediumNewFinding() {
        Map<String, DoubleCheckOutcome> result = check(List.of(candidate("a")),
                new DoubleCheckSettings(true, DoubleCheckSettings.Severity.HIGH, null), new Budget(100_000, 8));
        assertThat(result.get("a").verdict()).isEqualTo(Verdict.SKIPPED);
        verifyNoInteractions(client, integrations);
    }

    @Test
    void callFailureFailsEveryFindingInBatch() {
        when(client.submitReviewPrompt(anyString(), isNull(), anyString())).thenThrow(new IllegalStateException("offline"));
        assertThat(check(List.of(candidate("a"), candidate("b"))).values()).allMatch(DoubleCheckOutcome::failed);
    }

    @Test
    void malformedJsonFailsEveryFinding() {
        when(client.submitReviewPrompt(anyString(), isNull(), anyString())).thenReturn("not JSON");
        assertThat(check(List.of(candidate("a"), candidate("b"))).values()).allMatch(DoubleCheckOutcome::failed);
    }

    @Test
    void missingDuplicateUnknownAndInvalidVerdictsDoNotPoisonGoodIds() {
        when(client.submitReviewPrompt(anyString(), isNull(), anyString())).thenReturn(response(verdict("a", "keep"),
                verdict("b", "keep"), verdict("b", "drop"), verdict("unknown", "keep"), verdict("d", "approve")));
        Map<String, DoubleCheckOutcome> result = check(List.of(candidate("a"), candidate("b"), candidate("c"), candidate("d")));
        assertThat(result.get("a").verdict()).isEqualTo(Verdict.KEEP);
        assertThat(result.get("b").failed()).isTrue();
        assertThat(result.get("c").failed()).isTrue();
        assertThat(result.get("d").failed()).isTrue();
        assertThat(result).doesNotContainKey("unknown");
    }

    @Test
    void trailingContentAndDuplicateJsonKeysAreRejected() {
        when(client.submitReviewPrompt(anyString(), isNull(), anyString())).thenReturn(
                response(verdict("a", "keep")) + " {}",
                "{\"verdicts\":[],\"verdicts\":[" + verdict("a", "keep") + "]}");
        assertThat(check(List.of(candidate("a"))).get("a").failed()).isTrue();
        assertThat(check(List.of(candidate("a"))).get("a").failed()).isTrue();
    }

    @Test
    void missingReasonFailsOnlyAffectedFinding() {
        when(client.submitReviewPrompt(anyString(), isNull(), anyString())).thenReturn(response(
                "{\"id\":\"a\",\"verdict\":\"keep\"}", verdict("b", "keep")));
        Map<String, DoubleCheckOutcome> result = check(List.of(candidate("a"), candidate("b")));
        assertThat(result.get("a").failed()).isTrue();
        assertThat(result.get("b").verdict()).isEqualTo(Verdict.KEEP);
    }

    @Test
    void oversizedContextIsTrimmedToFiveLines() throws Exception {
        when(repository.getFileContent("o", "r", "Main.java", HEAD)).thenReturn(
                IntStream.rangeClosed(1, 40).mapToObj(i -> "x".repeat(100)).collect(Collectors.joining("\n")));
        when(client.submitReviewPrompt(anyString(), isNull(), anyString())).thenReturn(response(verdict("a", "keep")));
        assertThat(check(List.of(candidate("a")), DoubleCheckSettings.defaults(), new Budget(2600, 8)).get("a").verdict()).isEqualTo(Verdict.KEEP);
        String context = request().path("findings").get(0).path("sourceContext").asText();
        assertThat(context).startsWith("15: ").contains("25: ").doesNotContain("14: ", "26: ");
    }

    @Test
    void stillOversizedFindingIsHeldWithoutAiCall() {
        assertThat(check(List.of(candidate("a")), DoubleCheckSettings.defaults(), new Budget(100, 8)).get("a").held()).isTrue();
        verifyNoInteractions(client);
    }

    @Test
    void chunksRequestsAndHoldsBeyondChunkLimit() {
        when(client.submitReviewPrompt(anyString(), isNull(), anyString())).thenAnswer(invocation -> {
            JsonNode request = new ObjectMapper().readTree((String) invocation.getArgument(2));
            String[] verdicts = new String[request.path("findings").size()];
            for (int i = 0; i < verdicts.length; i++) verdicts[i] = verdict(request.path("findings").get(i).path("id").asText(), "keep");
            return response(verdicts);
        });
        Map<String, DoubleCheckOutcome> result = check(List.of(candidate("a"), candidate("b"), candidate("c")),
                DoubleCheckSettings.defaults(), new Budget(1700, 1));
        assertThat(result.get("a").verdict()).isEqualTo(Verdict.KEEP);
        assertThat(result.get("b").held()).isTrue();
        assertThat(result.get("c").held()).isTrue();
        verify(client, times(1)).submitReviewPrompt(anyString(), isNull(), anyString());
    }

    @Test
    void validIntegrationUsesSecondClient() {
        AiIntegration integration = new AiIntegration();
        AiClient second = mock(AiClient.class);
        when(integrations.findById(42L)).thenReturn(Optional.of(integration));
        when(factory.getClient(integration)).thenReturn(second);
        when(second.submitReviewPrompt(anyString(), isNull(), anyString())).thenReturn(response(verdict("a", "drop")));
        assertThat(check(List.of(candidate("a")), new DoubleCheckSettings(true, DoubleCheckSettings.Severity.LOW, 42L),
                new Budget(100_000, 8)).get("a").verdict()).isEqualTo(Verdict.DROP);
        verifyNoInteractions(client, audits);
    }

    @Test
    void missingIntegrationFallsBackAndAuditsWarning() {
        when(integrations.findById(42L)).thenReturn(Optional.empty());
        when(client.submitReviewPrompt(anyString(), isNull(), anyString())).thenReturn(response(verdict("a", "keep")));
        check(List.of(candidate("a")), new DoubleCheckSettings(true, DoubleCheckSettings.Severity.LOW, 42L), new Budget(100_000, 8));
        verify(audits).recordGroundingWarning(eq(warning), eq("double-check-config"), anyString(), eq(HEAD), eq(BASE));
    }

    @Test
    void diffOnlyDoesNotReadSourcesOrRecheckPriors() throws Exception {
        evidence = new Evidence(null, null, DIFF);
        when(client.submitReviewPrompt(anyString(), isNull(), anyString())).thenReturn(response(verdict("a", "keep")));
        Map<String, DoubleCheckOutcome> result = check(List.of(candidate("a"), prior("prior", true, "disputed")));
        assertThat(result.get("prior").verdict()).isEqualTo(Verdict.SKIPPED);
        assertThat(request().path("evidenceMode").asText()).contains("diff-only");
        assertThat(request().path("findings").get(0).has("sourceContext")).isFalse();
        verifyNoInteractions(repository);
    }

    @Test
    void leftSideUsesEffectiveBaseAndCitationsCarryContext() throws Exception {
        when(repository.getFileContent("o", "r", "Main.java", BASE)).thenReturn("oldCall();");
        ReviewDocument.Finding finding = new ReviewDocument.Finding("a", "defect", "Main.java", DiffSide.OLD, 1,
                "HIGH", "correctness", "oldCall();", List.of(new ReviewDocument.Citation("Other.java", "codeAtLine20();")));
        when(client.submitReviewPrompt(anyString(), isNull(), anyString())).thenReturn(response(verdict("a", "keep")));
        check(List.of(new Candidate(finding, new QuoteMatch.MatchedSpan(1, 1), false, false, "")));
        JsonNode packaged = request().path("findings").get(0);
        assertThat(packaged.path("sourceContext").asText()).contains("oldCall();");
        assertThat(packaged.path("citations").get(0).path("sourceContext").asText()).contains("codeAtLine20();");
        verify(repository).getFileContent("o", "r", "Main.java", BASE);
    }

    @Test
    void unreadableEvidenceFailsOnlyAffectedCandidate() {
        when(repository.getFileContent("o", "r", "Main.java", HEAD)).thenThrow(new IllegalStateException());
        assertThat(check(List.of(candidate("a"))).get("a").failed()).isTrue();
        verifyNoInteractions(client);
    }

    @Test
    void duplicateCandidateIdsAreRejectedBeforeAnyCall() {
        assertThatThrownBy(() -> check(List.of(candidate("a"), candidate("a")))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(client);
    }

    @Test
    void cancelledRunDoesNotCallAi() {
        assertThatThrownBy(() -> service.check(List.of(candidate("a")), evidence, DoubleCheckSettings.defaults(), client,
                new Budget(100_000, 8), warning, stage -> { throw new WorkflowCancelledException("cancelled"); }))
                .isInstanceOf(WorkflowCancelledException.class);
        verifyNoInteractions(client);
    }

    @Test
    void blankAndZeroIntegrationParamsUseReviewClientAndDefaults() {
        assertThat(DoubleCheckSettings.fromParams(Map.of())).isEqualTo(DoubleCheckSettings.defaults());
        assertThat(DoubleCheckSettings.fromParams(Map.of("doubleCheckAiIntegrationId", "")).aiIntegrationId()).isNull();
        assertThat(DoubleCheckSettings.fromParams(Map.of("doubleCheckAiIntegrationId", 0)).aiIntegrationId()).isNull();
        assertThat(DoubleCheckSettings.fromParams(Map.of("doubleCheckAiIntegrationId", 42)).aiIntegrationId()).isEqualTo(42L);
        assertThat(DoubleCheckSettings.Severity.parse(null)).isEqualTo(DoubleCheckSettings.Severity.LOW);
    }

    private Map<String, DoubleCheckOutcome> check(List<Candidate> candidates) {
        return check(candidates, DoubleCheckSettings.defaults(), new Budget(100_000, 8));
    }
    private Map<String, DoubleCheckOutcome> check(List<Candidate> candidates, DoubleCheckSettings settings, Budget budget) {
        return service.check(candidates, evidence, settings, client, budget, warning, ReviewFence.NONE);
    }
    private JsonNode request() throws Exception {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(client).submitReviewPrompt(anyString(), isNull(), captor.capture());
        return new ObjectMapper().readTree(captor.getValue());
    }
    private static Candidate candidate(String id) { return prior(id, false, "", false); }
    private static Candidate prior(String id, boolean changed, String dispute) { return prior(id, changed, dispute, true); }
    private static Candidate prior(String id, boolean changed, String dispute, boolean prior) {
        return new Candidate(new ReviewDocument.Finding(id, "defect", "Main.java", DiffSide.NEW, 20,
                "MEDIUM", "correctness", "codeAtLine20();", List.of()), new QuoteMatch.MatchedSpan(20, 20), prior, changed, dispute);
    }
    private static String verdict(String id, String value) {
        return "{\"id\":\"" + id + "\",\"verdict\":\"" + value + "\",\"reason\":\"verified\"}";
    }
    private static String response(String... verdicts) { return "{\"verdicts\":[" + String.join(",", verdicts) + "]}"; }
}
