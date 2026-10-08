package org.remus.giteabot.review;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.remus.giteabot.audit.PrAuditEvent;
import org.remus.giteabot.audit.PrAuditEventService;
import org.remus.giteabot.repository.PostReviewAction;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.model.CommentScan;
import org.remus.giteabot.repository.model.DiffSide;
import org.remus.giteabot.repository.model.ReviewComment;
import org.remus.giteabot.repository.model.ReviewPublicationResult;
import org.remus.giteabot.repository.model.ReviewPublicationResult.CommentOutcome;
import org.remus.giteabot.repository.model.ReviewPublicationResult.Delivery;
import org.remus.giteabot.repository.model.ReviewPublicationResult.Status;
import org.remus.giteabot.repository.model.ReviewSnapshot;
import org.remus.giteabot.review.grounding.FindingGroundingService;
import org.remus.giteabot.review.grounding.GroundingResult;
import org.remus.giteabot.review.state.ReviewFindingRecord;
import org.remus.giteabot.review.state.ReviewRunLeaseService;
import org.remus.giteabot.review.state.ReviewStateService;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReviewPublicationServiceGroundedTest {
    private static final String DIFF = "diff --git a/A.java b/A.java\n--- a/A.java\n+++ b/A.java\n@@ -1 +1 @@\n-oldExpression();\n+newExpression();\n";
    private static final ReviewSnapshot SNAPSHOT = new ReviewSnapshot("a".repeat(40), "b".repeat(40), null, DIFF, true);
    private final RepositoryApiClient api = mock(RepositoryApiClient.class);
    private final ReviewStateService state = mock(ReviewStateService.class);
    private final ReviewRunLeaseService leases = mock(ReviewRunLeaseService.class);
    private final PrAuditEventService audit = mock(PrAuditEventService.class);
    private final PrAuditEvent event = new PrAuditEvent();
    private final ReviewRunLeaseService.LeaseToken lease = new ReviewRunLeaseService.LeaseToken(1L, "o", "r", 2L, "run", 1);
    private final String id = UUID.randomUUID().toString();
    private final ReviewDocument.Finding finding = new ReviewDocument.Finding(id, "Issue", "A.java", DiffSide.NEW, 1,
            "HIGH", "bug", "newExpression();", List.of());

    @Test
    void pendingWrittenBeforeInlinePublicationWithMarkerAndRemoteIds() {
        when(api.publishInlineReview(any(), any(), any(), any(), any(), anyList(), any())).thenAnswer(inv -> {
            verify(state).recordPending(any(), eq(lease));
            List<org.remus.giteabot.repository.model.ReviewAnchorComment> anchors = inv.getArgument(5);
            assertThat(anchors.getFirst().body()).contains(ReviewFindingRecord.markerFor(UUID.fromString(id)));
            return new ReviewPublicationResult(Status.PUBLISHED, Delivery.CONFIRMED,
                    List.of(new CommentOutcome(anchors.getFirst(), Delivery.CONFIRMED, "10")), null);
        });
        ReviewPublicationService.Outcome result = publish(grounded(), SNAPSHOT);
        assertThat(result.remoteIds()).containsEntry(id, "10");
        verify(state).markPublished(UUID.fromString(id), "10", "10", lease);
    }

    @Test
    void summaryOnlyTrackedWithMarkerNotesAndSharedCommentId() {
        when(api.postReviewReturningId(any(), any(), any(), anyString(), any())).thenReturn(Optional.of("20"));
        ReviewPublicationService.Outcome result = publish(grounded(), ReviewSnapshot.summaryOnly(DIFF));
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(api).postReviewReturningId(eq("o"), eq("r"), eq(2L), body.capture(), eq(PostReviewAction.NONE));
        assertThat(body.getValue()).contains("summary note", ReviewFindingRecord.markerFor(UUID.fromString(id)));
        ArgumentCaptor<ReviewFindingRecord> pending = ArgumentCaptor.forClass(ReviewFindingRecord.class);
        verify(state).recordPending(pending.capture(), eq(lease));
        assertThat(pending.getValue().isSummaryOnly()).isTrue();
        assertThat(pending.getValue().getQuoteHash()).isEqualTo("quote-hash");
        assertThat(result.remoteIds()).containsEntry(id, "20");
        verify(state).markPublished(UUID.fromString(id), "20", null, lease);
    }

    @Test
    void failedWriteLeavesPendingForReconciliation() {
        when(api.postReviewReturningId(any(), any(), any(), anyString(), any())).thenThrow(new IllegalStateException("offline"));
        assertThatThrownBy(() -> publish(grounded(), ReviewSnapshot.summaryOnly(DIFF))).isInstanceOf(IllegalStateException.class);
        verify(state).recordPending(any(), eq(lease));
        verify(state, never()).markPublished(any(), any(), any(), any());
    }

    @Test
    void leaseLostAfterProviderSuccessLeavesPendingAndNoStateWrites() {
        when(api.postReviewReturningId(any(), any(), any(), anyString(), any())).thenAnswer(inv -> {
            doThrow(new ReviewRunLeaseService.LeaseLostException(lease)).when(leases).assertHeld(lease);
            return Optional.of("20");
        });
        assertThatThrownBy(() -> publish(grounded(), ReviewSnapshot.summaryOnly(DIFF)))
                .isInstanceOf(ReviewRunLeaseService.LeaseLostException.class);
        verify(state, never()).markPublished(any(), any(), any(), any());
        verify(audit).recordGroundingWarning(event, "PUBLICATION", "lease lost", null, null);
    }

    @Test
    void leaseLostBeforePendingCausesNoStateOrProviderWrites() {
        doThrow(new ReviewRunLeaseService.LeaseLostException(lease)).when(leases).assertHeld(lease);
        assertThatThrownBy(() -> publish(grounded(), SNAPSHOT)).isInstanceOf(ReviewRunLeaseService.LeaseLostException.class);
        verifyNoInteractions(state, api);
    }

    @Test
    void supersededOnlyAfterSuccessfulPublication() {
        UUID old = UUID.randomUUID();
        GroundingResult grounded = new GroundingResult(List.of(finding), List.of(), List.of(), List.of(),
                grounded().evidence(), Map.of(id, List.of(old)), false);
        when(api.postReviewReturningId(any(), any(), any(), anyString(), any())).thenThrow(new IllegalStateException("offline"))
                .thenReturn(Optional.of("20"));
        assertThatThrownBy(() -> publish(grounded, ReviewSnapshot.summaryOnly(DIFF))).isInstanceOf(IllegalStateException.class);
        verify(state, never()).markSuperseded(any(), any());
        publish(grounded, ReviewSnapshot.summaryOnly(DIFF));
        verify(state).markSuperseded(old, lease);
    }

    @Test
    void partialPublicationDoesNotReplayConfirmedComments() {
        String otherId = UUID.randomUUID().toString();
        ReviewDocument.Finding other = new ReviewDocument.Finding(otherId, "Other", "A.java", DiffSide.NEW, 1,
                "HIGH", "other", "newExpression();", List.of());
        GroundingResult grounded = new GroundingResult(List.of(finding, other), List.of(), List.of(), List.of(),
                Map.of(id, grounded().evidence().get(id), otherId, grounded().evidence().get(id)), Map.of(), false);
        when(api.publishInlineReview(any(), any(), any(), any(), any(), anyList(), any())).thenAnswer(inv -> {
            List<org.remus.giteabot.repository.model.ReviewAnchorComment> anchors = inv.getArgument(5);
            return new ReviewPublicationResult(Status.PARTIAL, Delivery.CONFIRMED,
                    List.of(new CommentOutcome(anchors.get(0), Delivery.CONFIRMED, "10"),
                            new CommentOutcome(anchors.get(1), Delivery.FAILED, null)), "partial");
        });
        when(api.postReviewCommentReturningId(any(), any(), any(), anyString())).thenReturn(Optional.of("20"));
        ReviewPublicationService.Outcome result = publish(grounded, SNAPSHOT);
        verify(api).postReviewCommentReturningId(any(), any(), any(), argThat(body -> body.contains("Other") && !body.contains("— Issue")));
        assertThat(result.remoteIds()).containsEntry(id, "10").containsEntry(otherId, "20");
        verify(state).markPublished(UUID.fromString(otherId), "20", null, lease);
    }

    @Test
    void pendingReviewConflictUsesTrackedLinkedFallback() {
        when(api.publishInlineReview(any(), any(), any(), any(), any(), anyList(), any())).thenAnswer(inv ->
                ReviewPublicationResult.notWritten(Status.PENDING_CONFLICT, inv.getArgument(5), "pending"));
        when(api.postPullRequestCommentReturningId(any(), any(), any(), anyString())).thenReturn(Optional.of("20"));
        ReviewPublicationService.Outcome result = publish(grounded(), SNAPSHOT);
        assertThat(result.remoteIds()).containsEntry(id, "20");
        verify(api).postPullRequestCommentReturningId(any(), any(), any(), contains("pending review"));
        verify(state).markPublished(UUID.fromString(id), "20", null, lease);
    }

    @Test
    void parseFailureDoesNotPublishUnstructuredOriginalSummary() {
        GroundingResult failed = new GroundingResult(List.of(), List.of(FindingGroundingService.PARSE_FAILURE), List.of(),
                List.of(), Map.of(), Map.of(), true);
        publish(failed, SNAPSHOT);
        verify(api).postReviewReturningId(any(), any(), any(), eq(FindingGroundingService.PARSE_FAILURE), any());
        verifyNoInteractions(state);
    }

    @Test
    void gitlabDiscussionAndNoteIdsStoredSeparately() {
        ReviewComment root = mock(ReviewComment.class);
        when(root.getId()).thenReturn(55L);
        when(root.getThreadId()).thenReturn("discussion");
        when(root.getBody()).thenReturn(ReviewFindingRecord.markerFor(UUID.fromString(id)));
        when(api.listReviewThreadComments(any(), any(), any())).thenReturn(new CommentScan<>(List.of(root), true));
        when(api.publishInlineReview(any(), any(), any(), any(), any(), anyList(), any())).thenAnswer(inv -> {
            List<org.remus.giteabot.repository.model.ReviewAnchorComment> anchors = inv.getArgument(5);
            return new ReviewPublicationResult(Status.PUBLISHED, Delivery.CONFIRMED,
                    List.of(new CommentOutcome(anchors.getFirst(), Delivery.CONFIRMED, "discussion")), null);
        });
        publish(grounded(), SNAPSHOT);
        verify(state).markPublished(UUID.fromString(id), "55", "discussion", lease);
    }

    private GroundingResult grounded() {
        return new GroundingResult(List.of(finding), List.of("summary note"), List.of(), List.of(),
                Map.of(id, new GroundingResult.FindingEvidence("quote-hash", "context-hash", finding.quote())), Map.of(), false);
    }
    private ReviewPublicationService.Outcome publish(GroundingResult grounded, ReviewSnapshot snapshot) {
        return ReviewPublicationService.publish(api, snapshot, "unverified original summary", grounded, PostReviewAction.NONE,
                UnaryOperator.identity(), ReviewFence.NONE, state, leases, lease, "review", audit, event);
    }
}
