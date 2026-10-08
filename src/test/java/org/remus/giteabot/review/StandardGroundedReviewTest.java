package org.remus.giteabot.review;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.audit.PrAuditEvent;
import org.remus.giteabot.audit.PrAuditEventService;
import org.remus.giteabot.config.ReviewConfigProperties;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.repository.PostReviewAction;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.model.ReviewSnapshot;
import org.remus.giteabot.review.grounding.*;
import org.remus.giteabot.review.state.*;
import org.remus.giteabot.session.ReviewSession;
import org.remus.giteabot.session.SessionService;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.remus.giteabot.review.grounding.DoubleCheckService.*;

class StandardGroundedReviewTest {
    private static final String HEAD = "a".repeat(40);
    private static final String BASE = "b".repeat(40);
    private static final String TARGET = "c".repeat(40);
    private static final String QUOTE = "return value();";
    private static final String DIFF = "diff --git a/A.java b/A.java\n--- a/A.java\n+++ b/A.java\n"
            + "@@ -1,1 +1,1 @@\n-return previous();\n+return value();\n";
    private final RepositoryApiClient api = mock(RepositoryApiClient.class);
    private final AiClient ai = mock(AiClient.class);
    private final SessionService sessions = mock(SessionService.class);
    private final ReviewStateService state = mock(ReviewStateService.class);
    private final ReviewRunLeaseService leases = mock(ReviewRunLeaseService.class);
    private final PriorFindingsService priors = mock(PriorFindingsService.class);
    private final DoubleCheckService checks = mock(DoubleCheckService.class);
    private final PrAuditEventService audit = mock(PrAuditEventService.class);
    private final ThreadActionExecutor threads = mock(ThreadActionExecutor.class);
    private final ReviewSkipService skip = mock(ReviewSkipService.class);
    private final ReviewRunLeaseService.LeaseToken lease = new ReviewRunLeaseService.LeaseToken(1L, "o", "r", 7L, "worker", 3);
    private final WebhookPayload payload = new WebhookPayload();
    private final ReviewSession session = new ReviewSession("o", "r", 7L, "prompt:1");
    private final PriorFindingsDigest empty = new PriorFindingsDigest(List.of(), true, null);
    private final FindingGroundingService grounding = spy(new FindingGroundingService(checks, audit));
    private CodeReviewService service;

    @BeforeEach
    void setup() {
        WebhookPayload.Owner owner = new WebhookPayload.Owner(); owner.setLogin("o");
        WebhookPayload.Repository repository = new WebhookPayload.Repository();
        repository.setOwner(owner); repository.setName("r"); payload.setRepository(repository);
        WebhookPayload.PullRequest pr = new WebhookPayload.PullRequest();
        pr.setNumber(7L); pr.setTitle("Fresh PR"); payload.setPullRequest(pr);
        when(leases.acquire(1L, "o", "r", 7L)).thenReturn(lease);
        when(api.getReviewSnapshot("o", "r", 7L)).thenReturn(new ReviewSnapshot(HEAD, TARGET, BASE, DIFF, false));
        when(api.getFileContent("o", "r", "A.java", HEAD)).thenReturn(QUOTE);
        when(priors.load(api, lease, "review", "bot")).thenReturn(empty);
        when(sessions.getOrCreateSession("o", "r", 7L, "prompt:1")).thenReturn(session);
        when(ai.submitReviewPrompt(anyString(), isNull(), anyString())).thenReturn(envelope("NEW", QUOTE, "new issue"));
        when(checks.check(anyList(), any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            List<Candidate> candidates = inv.getArgument(0);
            return candidates.stream().collect(Collectors.toMap(c -> c.finding().id(),
                    c -> new DoubleCheckOutcome(Verdict.KEEP, "verified")));
        });
        service = service(120000, Duration.ofMinutes(10));
    }

    @Test
    void leaseReleaseFailureDoesNotReplaceSuccessfulReview() {
        doThrow(new IllegalStateException("database unavailable")).when(leases).release(lease);
        assertThat(run(PostReviewAction.APPROVE).reviewed()).isTrue();
        verify(state).upsertRevision(any(), eq(lease));
        verify(leases).release(lease);
    }

    @Test
    void reconcilesAfterLeaseAndInjectsHistoryThenWritesRevisionAndActionBeforeRelease() {
        ReviewFindingRecord prior = new ReviewFindingRecord(); prior.setId(UUID.randomUUID());
        prior.setStatus(ReviewFindingStatus.GONE); prior.setBody("old claim"); prior.setHeadSha("old head");
        PriorFindingsDigest.CommentCursor cursor = new PriorFindingsDigest.CommentCursor(Instant.now(), "42");
        when(priors.load(api, lease, "review", "bot")).thenReturn(new PriorFindingsDigest(List.of(
                new PriorFindingsDigest.PriorFinding(prior, List.of(), false, null)), true, cursor));
        assertThat(run(PostReviewAction.APPROVE).reviewed()).isTrue();
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(ai).submitReviewPrompt(anyString(), isNull(), prompt.capture());
        assertThat(prompt.getValue()).contains("History to evaluate — NOT current code", "old claim", DIFF);
        ArgumentCaptor<ReviewRevisionRecord> revision = ArgumentCaptor.forClass(ReviewRevisionRecord.class);
        verify(state).upsertRevision(revision.capture(), eq(lease));
        assertThat(revision.getValue().getEffectiveBaseSha()).isEqualTo(BASE);
        assertThat(revision.getValue().getLastSeenCommentId()).isEqualTo("42");
        assertThat(revision.getValue().isDegraded()).isFalse();
        var order = inOrder(leases, api, priors, ai, state, sessions);
        order.verify(leases).acquire(1L, "o", "r", 7L);
        order.verify(api).getReviewSnapshot("o", "r", 7L);
        order.verify(priors).load(api, lease, "review", "bot");
        order.verify(ai).submitReviewPrompt(anyString(), isNull(), anyString());
        order.verify(state).recordPending(any(), eq(lease));
        order.verify(api).postReviewReturningId(eq("o"), eq("r"), eq(7L), contains("new issue"), eq(PostReviewAction.NONE));
        order.verify(state).markPublished(any(), any(), any(), eq(lease));
        order.verify(state).upsertRevision(any(), eq(lease));
        order.verify(sessions).addMessage(eq(session), eq("assistant"), contains("new issue"));
        order.verify(api).postReviewAction("o", "r", 7L, PostReviewAction.APPROVE);
        order.verify(leases).release(lease);
    }

    @Test
    void pinnedEnrichmentFailureStopsBeforeAiAndAlwaysReleases() {
        when(api.getFileContent("o", "r", "A.java", HEAD)).thenThrow(new IllegalStateException("missing commit"));
        assertThatThrownBy(() -> run(null)).isInstanceOf(PinnedSourceReader.RevisionUnavailableException.class);
        verify(ai, never()).submitReviewPrompt(any(), any(), any());
        verify(api, never()).postReviewReturningId(any(), any(), any(), any(), any());
        verify(state, never()).upsertRevision(any(), any());
        verify(leases).release(lease);
    }

    @Test
    void leftQuoteReadsEffectiveBaseNeverTargetTip() {
        when(ai.submitReviewPrompt(anyString(), isNull(), anyString())).thenReturn(envelope("OLD", "return previous();", "old-side issue"));
        when(api.getFileContent("o", "r", "A.java", BASE)).thenReturn("return previous();");
        assertThat(run(null).reviewed()).isTrue();
        verify(api).getFileContent("o", "r", "A.java", BASE);
        verify(api, never()).getFileContent(any(), any(), any(), eq(TARGET));
    }

    @Test
    void unreadableSupportingCitationDropsFindingAndDegradesReview() {
        when(ai.submitReviewPrompt(anyString(), isNull(), anyString())).thenReturn("""
                {"summary":"Review","findings":[{"body":"citation issue","citations":[{"path":"Missing.java","quote":"verifyAccess();"}]}]}
                """);
        assertThat(run(null).reviewed()).isTrue();
        verify(api).postReviewReturningId(any(), any(), any(), argThat(body -> !body.contains("citation issue")), any());
        verify(state).upsertRevision(argThat(ReviewRevisionRecord::isDegraded), eq(lease));
        verify(leases).release(lease);
    }

    @Test
    void unparseableOutputFailsClosedAndNeverPollutesChat() {
        when(ai.submitReviewPrompt(anyString(), isNull(), anyString())).thenReturn("fabricated raw model review");
        assertThat(run(null).reviewed()).isTrue();
        verify(api).postReviewReturningId(eq("o"), eq("r"), eq(7L), contains(FindingGroundingService.PARSE_FAILURE), any());
        verify(sessions, never()).addMessage(any(), eq("assistant"), contains("fabricated raw"));
        verify(audit).recordGroundingWarning(argThat(e -> e.getRunId().equals(99L)), eq("PARSE"), any(), eq(HEAD), eq(BASE));
        verify(state).upsertRevision(argThat(ReviewRevisionRecord::isDegraded), eq(lease));
    }

    @Test
    void unpinnedDiffOnlyRunReadsNoBranchCodeAndTracksDegradedSummary() {
        when(api.getReviewSnapshot("o", "r", 7L)).thenReturn(new ReviewSnapshot(HEAD, TARGET, null, DIFF, true));
        assertThat(run(null).reviewed()).isTrue();
        verify(api, never()).getFileContent(any(), any(), any(), any());
        verify(api, never()).getRepositoryTree(any(), any(), any());
        verify(api, never()).publishInlineReview(any(), any(), any(), any(), any(), any(), any());
        verify(state).recordPending(argThat(ReviewFindingRecord::isSummaryOnly), eq(lease));
        verify(state).upsertRevision(argThat(ReviewRevisionRecord::isDegraded), eq(lease));
    }

    @Test
    void failedThreadActionDegradesPublishedRevision() {
        when(threads.execute(eq(api), eq(lease), anyList(), eq("bot"), any(), eq(HEAD), eq(BASE), any()))
                .thenReturn(List.of(new ThreadAction.Withdraw(new PriorFindingsDigest.PriorFinding(
                        new ReviewFindingRecord(), List.of(), false, null), "failed")));
        run(null);
        verify(state).upsertRevision(argThat(ReviewRevisionRecord::isDegraded), eq(lease));
    }

    @Test
    void publicationFailureNeverMarksRevisionReviewed() {
        doThrow(new IllegalStateException("provider write failed")).when(api).postReviewReturningId(any(), any(), any(), any(), any());
        assertThatThrownBy(() -> run(null)).hasMessage("provider write failed");
        verify(state, never()).upsertRevision(any(), any());
        verify(sessions, never()).addMessage(any(), any(), any());
        verify(leases).release(lease);
    }

    @Test
    void leaseLossAfterAiPreventsPublicationStateAndSessionWrites() {
        when(ai.submitReviewPrompt(anyString(), isNull(), anyString())).thenAnswer(inv -> {
            doThrow(new ReviewRunLeaseService.LeaseLostException(lease)).when(leases).assertHeld(lease);
            return envelope("NEW", QUOTE, "unsafe stale issue");
        });
        assertThatThrownBy(() -> run(null)).isInstanceOf(ReviewRunLeaseService.LeaseLostException.class);
        verify(api, never()).postReviewReturningId(any(), any(), any(), any(), any());
        verify(state, never()).recordPending(any(), any());
        verify(state, never()).upsertRevision(any(), any());
        verify(sessions, never()).addMessage(any(), any(), any());
        verify(audit, atLeastOnce()).recordGroundingWarning(any(), any(), eq("lease lost"), any(), any());
        verify(leases).release(lease);
    }

    @Test
    void heartbeatRenewsWhileAiIsBlockedAndStopsAtRunCompletion() throws Exception {
        CountDownLatch renewed = new CountDownLatch(1);
        doAnswer(inv -> { renewed.countDown(); return null; }).when(leases).renew(lease);
        when(ai.submitReviewPrompt(anyString(), isNull(), anyString())).thenAnswer(inv -> {
            assertThat(renewed.await(2, TimeUnit.SECONDS)).isTrue();
            return envelope("NEW", QUOTE, "new issue");
        });
        service = service(120000, Duration.ofMillis(30));
        assertThat(run(null).reviewed()).isTrue();
        verify(leases, atLeastOnce()).renew(lease);
        verify(leases).release(lease);
    }

    @Test
    void chunkMergeAssignsUniqueIdsBeforeGrounding() {
        String second = DIFF.replace("A.java", "B.java");
        when(api.getReviewSnapshot("o", "r", 7L)).thenReturn(new ReviewSnapshot(HEAD, TARGET, BASE, DIFF + second, false));
        when(api.getFileContent("o", "r", "B.java", HEAD)).thenReturn(QUOTE);
        when(ai.submitReviewPrompt(anyString(), isNull(), anyString())).thenAnswer(inv -> {
            String prompt = inv.getArgument(2);
            String path = prompt.substring(prompt.indexOf("**Diff:**")).contains("B.java") ? "B.java" : "A.java";
            return envelope("NEW", QUOTE, "issue in " + path).replace("A.java", path);
        });
        service = service(DIFF.length() + 1, Duration.ofMinutes(10));
        run(null);
        ArgumentCaptor<List<Candidate>> candidates = ArgumentCaptor.forClass(List.class);
        verify(checks).check(candidates.capture(), any(), any(), any(), any(), any(), any());
        ArgumentCaptor<ReviewDocument> merged = ArgumentCaptor.forClass(ReviewDocument.class);
        verify(grounding).ground(merged.capture(), any(), any(), any(), any(), any(), any(), any(), any());
        assertThat(merged.getValue().findings()).hasSize(2).extracting(ReviewDocument.Finding::id).doesNotHaveDuplicates();
        assertThat(candidates.getValue()).hasSize(2);
        assertThat(candidates.getValue()).extracting(c -> c.finding().id()).doesNotHaveDuplicates();
        verify(ai, times(2)).submitReviewPrompt(any(), any(), any());
        verify(state, times(2)).recordPending(any(), eq(lease));
    }

    @Test
    void partialChunkFailureKeepsGroundedFindingsButDegradesRevision() {
        String second = DIFF.replace("A.java", "B.java");
        when(api.getReviewSnapshot("o", "r", 7L)).thenReturn(new ReviewSnapshot(HEAD, TARGET, BASE, DIFF + second, false));
        when(api.getFileContent("o", "r", "B.java", HEAD)).thenReturn(QUOTE);
        when(ai.submitReviewPrompt(anyString(), isNull(), anyString()))
                .thenReturn(envelope("NEW", QUOTE, "verified first chunk"))
                .thenThrow(new IllegalStateException("chunk unavailable"));
        service = service(DIFF.length() + 1, Duration.ofMinutes(10));
        assertThat(run(null).reviewed()).isTrue();
        verify(api).postReviewReturningId(eq("o"), eq("r"), eq(7L), contains("verified first chunk"), any());
        verify(state).recordPending(any(), eq(lease));
        verify(state).upsertRevision(argThat(ReviewRevisionRecord::isDegraded), eq(lease));
    }

    @Test
    void timeoutBeforeSnapshotPropagatesToWorkerFailureHandler() {
        when(leases.acquire(1L, "o", "r", 7L)).thenThrow(new ReviewRunLeaseService.LeaseAcquisitionTimeoutException(1L, "o", "r", 7L, Duration.ZERO));
        assertThatThrownBy(() -> run(null)).isInstanceOf(ReviewRunLeaseService.LeaseAcquisitionTimeoutException.class);
        verify(api, never()).getReviewSnapshot(any(), any(), any());
        verify(leases, never()).release(any());
    }

    private CodeReviewService service(int chunkChars, Duration ttl) {
        return new CodeReviewService(api, ai, sessions, "bot", new ReviewConfigProperties(), "prompt:1", "Review",
                chunkChars, 8, 60000, "", DoubleCheckSettings.defaults(), 1L, leases, state, priors,
                grounding, threads, audit, skip, ttl);
    }

    private CodeReviewService.ReviewRun run(PostReviewAction action) {
        return service.runReview(payload, null, 99L, false, action, ReviewFence.NONE);
    }

    private static String envelope(String side, String quote, String body) {
        return "{\"summary\":\"Review\",\"findings\":[{\"id\":\"reused-id\",\"path\":\"A.java\",\"side\":\""
                + side + "\",\"line\":1,\"severity\":\"HIGH\",\"body\":\"" + body + "\",\"quote\":\"" + quote + "\"}]}";
    }
}
