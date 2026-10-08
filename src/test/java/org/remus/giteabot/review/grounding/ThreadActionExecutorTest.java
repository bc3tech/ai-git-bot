package org.remus.giteabot.review.grounding;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.audit.AuditEventType;
import org.remus.giteabot.audit.PrAuditEvent;
import org.remus.giteabot.audit.PrAuditEventService;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.model.CommentScan;
import org.remus.giteabot.repository.model.ReviewComment;
import org.remus.giteabot.repository.model.ThreadCapabilities;
import org.remus.giteabot.review.ReviewFence;
import org.remus.giteabot.review.state.ReviewFindingRecord;
import org.remus.giteabot.review.state.ReviewFindingStatus;
import org.remus.giteabot.review.state.ReviewRunLeaseService;
import org.remus.giteabot.review.state.ReviewStateService;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ThreadActionExecutorTest {
    private final RepositoryApiClient api = mock(RepositoryApiClient.class);
    private final ReviewStateService state = mock(ReviewStateService.class);
    private final ReviewRunLeaseService leases = mock(ReviewRunLeaseService.class);
    private final PrAuditEventService audit = mock(PrAuditEventService.class);
    private final ThreadActionExecutor executor = new ThreadActionExecutor(state, leases, audit);
    private final ReviewRunLeaseService.LeaseToken lease = new ReviewRunLeaseService.LeaseToken(1L, "o", "r", 2L, "run", 1);
    private final PrAuditEvent event = new PrAuditEvent();
    private final ReviewFindingRecord record = new ReviewFindingRecord();
    private PriorFindingsDigest.PriorFinding prior;

    @BeforeEach
    void setup() {
        record.setId(UUID.randomUUID());
        record.setMarker(ReviewFindingRecord.markerFor(record.getId()));
        record.setStatus(ReviewFindingStatus.PUBLISHED);
        record.setRemoteCommentId("10");
        record.setRemoteThreadId("thread");
        record.setBody("Original issue");
        prior = new PriorFindingsDigest.PriorFinding(record,
                List.of(new PriorFindingsDigest.Reply("42", "human", "claim", null)), true, "42");
        when(api.threadCapabilities()).thenReturn(new ThreadCapabilities(true, true, true));
        when(api.listReviewThreadComments("o", "r", 2L)).thenReturn(new CommentScan<>(List.of(), true));
        when(api.pullRequestCommentLink("o", "r", 2L, "10")).thenReturn(Optional.of("https://provider/pr/2#comment-10"));
    }

    @Test
    void upheldReplyUpdatesAnsweredOnlyAfterSuccessfulReply() {
        execute(new ThreadAction.UpheldReply(prior, "Evidence confirms issue"));
        verify(api).replyToReviewThread(eq("o"), eq("r"), eq(2L), eq("thread"), contains(record.getMarker()));
        verify(state).updateLastAnsweredCommentId(record.getId(), "42", lease);
        verify(state, never()).markWithdrawn(any(), any());
    }

    @Test
    void withdrawalRepliesEditsResolvesAndRecordsAfterSuccess() {
        execute(new ThreadAction.Withdraw(prior, "Fixed"));
        verify(api).replyToReviewThread(eq("o"), eq("r"), eq(2L), eq("thread"), contains("Withdrawn: Fixed"));
        verify(api).editReviewComment(eq("o"), eq("r"), eq(2L), eq("10"), contains("**Withdrawn**"));
        verify(api).resolveReviewThread("o", "r", 2L, "thread");
        verify(state).markWithdrawn(record.getId(), lease);
        verify(audit).recordFindingDisposition(eq(event), eq(AuditEventType.FINDING_WITHDRAWN), eq(record.getId().toString()),
                any(), any(), any(), any(), any(), any(), eq("THREAD"), eq("Fixed"), eq("head"), eq("base"));
    }

    @Test
    void humanReplyAppearingImmediatelyBeforeResolvePreventsResolve() {
        ReviewComment reply = reply(43L);
        when(api.listReviewThreadComments("o", "r", 2L)).thenReturn(new CommentScan<>(List.of(reply), true));
        execute(new ThreadAction.Withdraw(prior, "Fixed"));
        verify(api, never()).resolveReviewThread(any(), any(), any(), any());
        verify(state).markWithdrawn(record.getId(), lease);
    }

    @Test
    void existingHumanReplyDoesNotPreventResolve() {
        ReviewComment existing = reply(42L);
        when(api.listReviewThreadComments("o", "r", 2L)).thenReturn(new CommentScan<>(List.of(existing), true));
        execute(new ThreadAction.Withdraw(prior, "Fixed"));
        verify(api).resolveReviewThread("o", "r", 2L, "thread");
    }

    @Test
    void incompleteScanDoesNotResolve() {
        when(api.listReviewThreadComments("o", "r", 2L)).thenReturn(new CommentScan<>(List.of(), false));
        execute(new ThreadAction.Withdraw(prior, "Fixed"));
        verify(api, never()).resolveReviewThread(any(), any(), any(), any());
    }

    @Test
    void giteaCapabilitiesUseLinkedCommentWithMarker() {
        when(api.threadCapabilities()).thenReturn(ThreadCapabilities.unsupported());
        execute(new ThreadAction.Withdraw(prior, "quote gone"));
        verify(api).postPullRequestCommentReturningId(eq("o"), eq("r"), eq(2L),
                argThat(body -> body.contains(record.getMarker()) && body.contains("https://provider/pr/2#comment-10")));
        verify(api, never()).replyToReviewThread(any(), any(), any(), any(), any());
        verify(state).markWithdrawn(record.getId(), lease);
    }

    @Test
    void summaryOnlyAlwaysLinkedNeverEditedOrResolved() {
        record.setSummaryOnly(true);
        execute(new ThreadAction.Withdraw(prior, "Fixed"));
        verify(api).postPullRequestCommentReturningId(any(), any(), any(), contains(record.getMarker()));
        verify(api, never()).replyToReviewThread(any(), any(), any(), any(), any());
        verify(api, never()).editReviewComment(any(), any(), any(), any(), any());
        verify(api, never()).resolveReviewThread(any(), any(), any(), any());
    }

    @Test
    void failedWithdrawalLeavesActiveAndNextRunRetries() {
        when(api.replyToReviewThread(any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("offline"))
                .thenReturn(Optional.of("reply"));
        ThreadAction action = new ThreadAction.Withdraw(prior, "Fixed");
        assertThat(execute(action)).containsExactly(action);
        verifyNoInteractions(state);
        assertThat(execute(action)).isEmpty();
        verify(state).markWithdrawn(record.getId(), lease);
    }

    @Test
    void unsupportedThreadReplyFallsBackToLinkedComment() {
        when(api.replyToReviewThread(any(), any(), any(), any(), any())).thenThrow(new UnsupportedOperationException());
        execute(new ThreadAction.UpheldReply(prior, "Still valid"));
        verify(api).postPullRequestCommentReturningId(any(), any(), any(), contains(record.getMarker()));
        verify(state).updateLastAnsweredCommentId(record.getId(), "42", lease);
    }

    @Test
    void lostLeaseBeforeFirstWriteStopsAllMutations() {
        doThrow(new ReviewRunLeaseService.LeaseLostException(lease)).when(leases).assertHeld(lease);
        assertThatThrownBy(() -> execute(new ThreadAction.Withdraw(prior, "Fixed")))
                .isInstanceOf(ReviewRunLeaseService.LeaseLostException.class);
        verify(api, never()).replyToReviewThread(any(), any(), any(), any(), any());
        verifyNoInteractions(state);
        verify(audit).recordGroundingWarning(event, "THREAD", "lease lost", "head", "base");
    }

    @Test
    void lostLeaseAfterReplyStopsEditResolveAndStateWrites() {
        when(api.replyToReviewThread(any(), any(), any(), any(), any())).thenAnswer(inv -> {
            doThrow(new ReviewRunLeaseService.LeaseLostException(lease)).when(leases).assertHeld(lease);
            return Optional.of("reply");
        });
        assertThatThrownBy(() -> execute(new ThreadAction.Withdraw(prior, "Fixed")))
                .isInstanceOf(ReviewRunLeaseService.LeaseLostException.class);
        verify(api, never()).editReviewComment(any(), any(), any(), any(), any());
        verify(api, never()).resolveReviewThread(any(), any(), any(), any());
        verifyNoInteractions(state);
    }

    @Test
    void gonePriorNeverActioned() {
        record.setStatus(ReviewFindingStatus.GONE);
        execute(new ThreadAction.Withdraw(prior, "Fixed"));
        verifyNoInteractions(api, state, leases);
    }

    @Test
    void missingThread404FallsBackWithoutLosingWithdrawal() {
        when(api.replyToReviewThread(any(), any(), any(), any(), any())).thenThrow(
                new org.springframework.web.client.HttpClientErrorException(org.springframework.http.HttpStatus.NOT_FOUND));
        execute(new ThreadAction.Withdraw(prior, "Fixed"));
        verify(api).postPullRequestCommentReturningId(any(), any(), any(), contains(record.getMarker()));
        verify(state).markWithdrawn(record.getId(), lease);
    }

    @Test
    void unpinnedExecutorDoesNotActionAnyPrior() {
        assertThat(executor.execute(api, lease, List.of(new ThreadAction.Withdraw(prior, "Fixed")), "bot", event,
                null, "base", ReviewFence.NONE)).isEmpty();
        verifyNoInteractions(api, state, leases);
    }

    private List<ThreadAction> execute(ThreadAction action) {
        return executor.execute(api, lease, List.of(action), "bot", event, "head", "base", ReviewFence.NONE);
    }
    private ReviewComment reply(Long id) {
        ReviewComment reply = mock(ReviewComment.class);
        when(reply.getId()).thenReturn(id);
        when(reply.getUserLogin()).thenReturn("human");
        when(reply.getThreadId()).thenReturn("thread");
        return reply;
    }
}
