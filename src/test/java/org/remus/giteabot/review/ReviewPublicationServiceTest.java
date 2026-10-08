package org.remus.giteabot.review;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.remus.giteabot.repository.PostReviewAction;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.model.DiffSide;
import org.remus.giteabot.repository.model.ReviewAnchorComment;
import org.remus.giteabot.repository.model.ReviewPublicationResult;
import org.remus.giteabot.repository.model.ReviewPublicationResult.CommentOutcome;
import org.remus.giteabot.repository.model.ReviewPublicationResult.Delivery;
import org.remus.giteabot.repository.model.ReviewPublicationResult.Status;
import org.remus.giteabot.repository.model.ReviewSnapshot;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewPublicationServiceTest {

    private static final String DIFF = """
            diff --git a/src/A.java b/src/A.java
            --- a/src/A.java
            +++ b/src/A.java
            @@ -1,3 +1,4 @@
             one
            -two
            +TWO
            +extra
             three
            """;
    private static final ReviewSnapshot INLINE = new ReviewSnapshot("h", "b", null, DIFF, true);
    private static final UnaryOperator<String> FRAME = body -> "[H]\n" + body + "\n[F]";

    private final RepositoryApiClient client = mock(RepositoryApiClient.class);

    private final ReviewDocument.Finding onAdded = finding("f1", "Use a constant.", "src/A.java", DiffSide.NEW, 2);
    private final ReviewDocument.Finding onRemoved = finding("f2", "Why remove this?", "src/A.java", DiffSide.OLD, 2);
    private final ReviewDocument.Finding outsideDiff = finding("f3", "Unrelated line.", "src/A.java", DiffSide.NEW, 40);
    private final ReviewDocument.Finding reviewWide = finding("f4", "Add tests.", null, null, null);

    @Test
    void allInlineReviewKeepsSummaryConciseAndDoesNotDuplicateDetail() {
        when(client.publishInlineReview(any(), any(), any(), any(), anyString(), anyList(), any()))
                .thenAnswer(inv -> confirmed(inv.getArgument(5)));

        ReviewPublicationService.Outcome outcome = publish(doc("Looks good overall.", onAdded, onRemoved),
                PostReviewAction.REQUEST_CHANGES, ReviewFence.NONE);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ReviewAnchorComment>> anchors = ArgumentCaptor.forClass(List.class);
        verify(client).publishInlineReview(eq("o"), eq("r"), eq(1L), eq(INLINE), body.capture(), anchors.capture(),
                eq(PostReviewAction.REQUEST_CHANGES));
        assertThat(body.getValue()).isEqualTo("[H]\nLooks good overall.\n[F]");
        assertThat(anchors.getValue()).extracting(ReviewAnchorComment::side)
                .containsExactly(DiffSide.NEW, DiffSide.OLD);
        assertThat(anchors.getValue().getFirst().body()).isEqualTo("**MEDIUM** · bug\n\nUse a constant.");
        assertThat(outcome.inlineConfirmed()).isEqualTo(2);
        assertThat(outcome.action()).isEqualTo(Delivery.CONFIRMED);
        verify(client, never()).postReview(any(), any(), any(), any(), any());
        verify(client, never()).postPullRequestComment(any(), any(), any(), any());
    }

    @Test
    void unplaceableAndReviewWideFindingsStayInSummaryWithoutPlacementNotes() {
        when(client.publishInlineReview(any(), any(), any(), any(), anyString(), anyList(), any()))
                .thenAnswer(inv -> confirmed(inv.getArgument(5)));

        publish(doc("Summary.", onAdded, outsideDiff, reviewWide), PostReviewAction.NONE, ReviewFence.NONE);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(client).publishInlineReview(any(), any(), any(), any(), body.capture(), anyList(), any());
        assertThat(body.getValue())
                .contains("### Other findings", "`src/A.java:40` — Unrelated line.", "Add tests.")
                .doesNotContain("Use a constant.")
                .doesNotContainIgnoringCase("could not");
    }

    @Test
    void summaryOnlySnapshotPostsEverythingAsOneReview() {
        ReviewPublicationService.Outcome outcome = ReviewPublicationService.publish(client, "o", "r", 1L,
                ReviewSnapshot.summaryOnly(DIFF), doc("", onAdded), PostReviewAction.APPROVE, FRAME, ReviewFence.NONE);

        verify(client).postReview("o", "r", 1L,
                "[H]\n### Findings\n\n- **MEDIUM** · bug `src/A.java:2` — Use a constant.\n\n[F]",
                PostReviewAction.APPROVE);
        verify(client, never()).publishInlineReview(any(), any(), any(), any(), any(), anyList(), any());
        assertThat(outcome.delivered()).isTrue();
    }

    @Test
    void emptyReviewSaysNoIssues() {
        publish(doc(""), PostReviewAction.NONE, ReviewFence.NONE);
        verify(client).postReview("o", "r", 1L, "[H]\nNo issues found.\n[F]", PostReviewAction.NONE);
    }

    @Test
    void rejectedOrStaleInlineReviewFallsBackToFullReviewWithAction() {
        when(client.publishInlineReview(any(), any(), any(), any(), anyString(), anyList(), any()))
                .thenAnswer(inv -> ReviewPublicationResult.notWritten(Status.STALE_SNAPSHOT, inv.getArgument(5), "moved"));

        ReviewPublicationService.Outcome outcome = publish(doc("S", onAdded), PostReviewAction.APPROVE, ReviewFence.NONE);

        verify(client).postReview(eq("o"), eq("r"), eq(1L), eq(
                "[H]\nS\n\n### Findings\n\n- **MEDIUM** · bug `src/A.java:2` — Use a constant.\n\n[F]"),
                eq(PostReviewAction.APPROVE));
        assertThat(outcome.fallbackUsed()).isTrue();
    }

    @Test
    void pendingConflictPostsPlainCommentAndDoesNotSubmitAction() {
        when(client.publishInlineReview(any(), any(), any(), any(), anyString(), anyList(), any()))
                .thenAnswer(inv -> ReviewPublicationResult.notWritten(Status.PENDING_CONFLICT, inv.getArgument(5), "p"));

        ReviewPublicationService.Outcome outcome = publish(doc("S", onAdded), PostReviewAction.REQUEST_CHANGES,
                ReviewFence.NONE);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(client).postPullRequestComment(eq("o"), eq("r"), eq(1L), body.capture());
        assertThat(body.getValue()).contains("Use a constant.", "pending review", "request-changes decision was not");
        verify(client, never()).postReview(any(), any(), any(), any(), any());
        assertThat(outcome.action()).isEqualTo(Delivery.FAILED);
    }

    @Test
    void partialDeliveryPostsOnlyUndeliveredDetailOnce() {
        when(client.publishInlineReview(any(), any(), any(), any(), anyString(), anyList(), any()))
                .thenAnswer(inv -> {
                    List<ReviewAnchorComment> requested = inv.getArgument(5);
                    return new ReviewPublicationResult(Status.PARTIAL, Delivery.CONFIRMED, List.of(
                            new CommentOutcome(requested.get(0), Delivery.CONFIRMED, "1"),
                            new CommentOutcome(requested.get(1), Delivery.UNKNOWN, null)), "timeout");
                });

        ReviewPublicationService.Outcome outcome = publish(doc("S", onAdded, onRemoved, outsideDiff),
                PostReviewAction.NONE, ReviewFence.NONE);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(client).postReviewComment(eq("o"), eq("r"), eq(1L), body.capture());
        assertThat(body.getValue())
                .contains("could not be confirmed", "Why remove this?")
                .doesNotContain("Use a constant.", "Unrelated line.");
        verify(client).publishInlineReview(any(), any(), any(), any(), anyString(), anyList(), any());
        assertThat(outcome.inlineConfirmed()).isEqualTo(1);
    }

    @Test
    void unknownReviewIncludesSummaryOnlyDetailInFallback() {
        when(client.publishInlineReview(any(), any(), any(), any(), anyString(), anyList(), any()))
                .thenAnswer(inv -> ReviewPublicationResult.unknown(inv.getArgument(5), "502"));

        publish(doc("Summary text", onAdded, reviewWide), PostReviewAction.NONE, ReviewFence.NONE);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(client).postReviewComment(any(), any(), any(), body.capture());
        assertThat(body.getValue()).contains("Summary text", "Use a constant.", "Add tests.", "may not have been");
        verify(client, never()).postPullRequestComment(any(), any(), any(), any());
    }

    @Test
    void cancellationAfterInlineWriteStopsFurtherWrites() {
        when(client.publishInlineReview(any(), any(), any(), any(), anyString(), anyList(), any()))
                .thenAnswer(inv -> ReviewPublicationResult.unknown(inv.getArgument(5), "502"));
        AtomicInteger checks = new AtomicInteger();
        ReviewFence fence = location -> {
            if (checks.incrementAndGet() > 1) {
                throw new IllegalStateException("superseded");
            }
        };

        assertThatThrownBy(() -> publish(doc("S", onAdded), PostReviewAction.NONE, fence))
                .hasMessage("superseded");
        verify(client, never()).postPullRequestComment(any(), any(), any(), any());
        verify(client, never()).postReviewComment(any(), any(), any(), any());
        verify(client, never()).postReview(any(), any(), any(), any(), any());
    }

    @Test
    void snapshotFailureDegradesToSummaryOnly() {
        when(client.getReviewSnapshot("o", "r", 1L)).thenThrow(new IllegalStateException("no versions"));
        when(client.getPullRequestDiff("o", "r", 1L)).thenReturn(DIFF);

        ReviewSnapshot snapshot = ReviewPublicationService.snapshot(client, "o", "r", 1L);

        assertThat(snapshot.inlineSupported()).isFalse();
        assertThat(snapshot.diff()).isEqualTo(DIFF);
    }

    private ReviewPublicationService.Outcome publish(ReviewDocument document, PostReviewAction action,
                                                     ReviewFence fence) {
        return ReviewPublicationService.publish(client, "o", "r", 1L, INLINE, document, action, FRAME, fence);
    }

    private static ReviewPublicationResult confirmed(List<ReviewAnchorComment> requested) {
        return new ReviewPublicationResult(Status.PUBLISHED, Delivery.CONFIRMED,
                requested.stream().map(c -> new CommentOutcome(c, Delivery.CONFIRMED, "x")).toList(), null);
    }

    private static ReviewDocument doc(String summary, ReviewDocument.Finding... findings) {
        return new ReviewDocument(summary, List.of(findings), true);
    }

    private static ReviewDocument.Finding finding(String id, String body, String path, DiffSide side, Integer line) {
        return new ReviewDocument.Finding(id, body, path, side, line, path == null ? null : "MEDIUM",
                path == null ? null : "bug");
    }
}
