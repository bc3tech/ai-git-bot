package org.remus.giteabot.review;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.GiteaClientFactory;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.prworkflow.PrWorkflowContext;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.model.CommentScan;
import org.remus.giteabot.repository.model.ReviewComment;
import org.remus.giteabot.repository.model.ReviewSnapshot;
import org.remus.giteabot.review.state.ReviewRevisionRecord;
import org.remus.giteabot.review.state.ReviewRunLeaseService;
import org.remus.giteabot.review.state.ReviewStateService;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReviewSkipServiceTest {
    @Mock private RepositoryApiClient api;
    @Mock private ReviewStateService state;
    @Mock private ReviewRunLeaseService leases;
    @Mock private GiteaClientFactory factory;
    private ReviewSkipService service;
    private ReviewRevisionRecord previous;
    private final Instant time = Instant.parse("2026-10-08T12:00:00Z");
    private final ReviewRunLeaseService.LeaseToken lease =
            new ReviewRunLeaseService.LeaseToken(1L, "owner", "repo", 7L, "worker", 1);
    private final ReviewSnapshot snapshot = new ReviewSnapshot("head-sha", "target-tip", "merge-base", "diff", true);

    @BeforeEach
    void setUp() {
        service = new ReviewSkipService(state, leases, factory);
        previous = new ReviewRevisionRecord();
        previous.setHeadSha("head-sha");
        previous.setEffectiveBaseSha("merge-base");
        previous.setReviewedAt(time);
        previous.setLastSeenCommentAt(time);
        previous.setLastSeenCommentId("10");
        lenient().when(state.findRevision(1L, "owner", "repo", 7L, "review")).thenAnswer(call -> Optional.of(previous));
        lenient().when(api.listReviewThreadComments("owner", "repo", 7L)).thenReturn(new CommentScan<>(List.of(), true));
        lenient().when(api.listPullRequestComments("owner", "repo", 7L)).thenReturn(new CommentScan<>(List.of(), true));
    }

    private boolean skip() {
        return service.shouldSkip(api, lease, "review", "bot", snapshot, false);
    }

    @Test void unchangedCleanRevisionSkips() { assertThat(skip()).isTrue(); }
    @Test void movingTargetTipDoesNotChangeEffectiveBase() {
        assertThat(service.shouldSkip(api, lease, "review", "bot",
                new ReviewSnapshot("head-sha", "new-target-tip", "merge-base", "diff", true), false)).isTrue();
    }
    @Test void changedMergeBaseReviews() { previous.setEffectiveBaseSha("older-base"); assertThat(skip()).isFalse(); }
    @Test void changedHeadReviews() { previous.setHeadSha("older-head"); assertThat(skip()).isFalse(); }
    @Test void degradedRunIncludingUnparseableOutputReviews() { previous.setDegraded(true); assertThat(skip()).isFalse(); }
    @Test void noSuccessfulRunReviews() { previous.setReviewedAt(null); assertThat(skip()).isFalse(); }
    @Test void missingRevisionReviews() {
        when(state.findRevision(1L, "owner", "repo", 7L, "review")).thenReturn(Optional.empty());
        assertThat(skip()).isFalse();
    }
    @Test void explicitRequestNeverSkips() {
        assertThat(service.shouldSkip(api, lease, "review", "bot", snapshot, true)).isFalse();
        verifyNoInteractions(leases, api);
    }
    @Test void unpinnedNeverSkips() {
        for (ReviewSnapshot unpinned : List.of(ReviewSnapshot.summaryOnly("diff"),
                new ReviewSnapshot("head-sha", "target-tip", null, "diff", true),
                new ReviewSnapshot(null, "target-tip", "merge-base", "diff", false),
                new ReviewSnapshot("head-sha", "target-tip", " ", "diff", true))) {
            assertThat(service.shouldSkip(api, lease, "review", "bot", unpinned, false)).isFalse();
        }
        verifyNoInteractions(leases, api);
    }
    private Map<String, Object> comment(String id, String timestamp, String login) {
        return Map.of("id", id, "created_at", timestamp, "user", Map.of("login", login), "body", "dispute");
    }
    private void prComment(Map<String, Object> comment) {
        when(api.listPullRequestComments("owner", "repo", 7L)).thenReturn(new CommentScan<>(List.of(comment), true));
    }
    @Test void newDisputeReviews() { prComment(comment("11", time.plusSeconds(1).toString(), "author")); assertThat(skip()).isFalse(); }
    @Test void equalTimestampUnseenIdReviews() { prComment(comment("11", time.toString(), "author")); assertThat(skip()).isFalse(); }
    @Test void previouslySeenCommentSkips() { prComment(comment("10", time.toString(), "author")); assertThat(skip()).isTrue(); }
    @Test void olderCommentSkips() { prComment(comment("9", time.minusSeconds(1).toString(), "author")); assertThat(skip()).isTrue(); }
    @Test void botCommentsDoNotCountAsNew() { prComment(comment("11", time.plusSeconds(1).toString(), "BOT")); assertThat(skip()).isTrue(); }
    @Test void missingCommentTimestampReviews() { prComment(Map.of("id", "11", "user", Map.of("login", "author"))); assertThat(skip()).isFalse(); }
    @Test void malformedCommentTimestampReviews() { prComment(comment("11", "invalid", "author")); assertThat(skip()).isFalse(); }
    @Test void missingCommentIdReviews() { prComment(Map.of("created_at", time.toString(), "user", Map.of("login", "author"))); assertThat(skip()).isFalse(); }
    @Test void missingAuthorReviews() { prComment(Map.of("id", "10", "created_at", time.toString())); assertThat(skip()).isFalse(); }
    @Test void missingCursorReviews() { previous.setLastSeenCommentId(null); prComment(comment("10", time.toString(), "author")); assertThat(skip()).isFalse(); }
    @Test void missingBotLoginReviews() { assertThat(service.shouldSkip(api, lease, "review", null, snapshot, false)).isFalse(); }
    @Test void blankCommentStillCountsAsNew() { prComment(Map.of("id", "11", "created_at", time.toString(), "user", Map.of("login", "author"), "body", "")); assertThat(skip()).isFalse(); }
    @Test void newInlineCommentReviews() {
        ReviewComment comment = mock(ReviewComment.class);
        when(comment.getUserLogin()).thenReturn("author");
        when(comment.getId()).thenReturn(11L);
        when(comment.getCreatedAt()).thenReturn(time.toString());
        when(api.listReviewThreadComments("owner", "repo", 7L)).thenReturn(new CommentScan<>(List.of(comment), true));
        assertThat(skip()).isFalse();
    }
    @Test void incompletePrScanReviews() { when(api.listPullRequestComments("owner", "repo", 7L)).thenReturn(new CommentScan<>(List.of(), false)); assertThat(skip()).isFalse(); }
    @Test void incompleteThreadScanReviews() { when(api.listReviewThreadComments("owner", "repo", 7L)).thenReturn(new CommentScan<>(List.of(), false)); assertThat(skip()).isFalse(); }
    @Test void failedScanReviews() { when(api.listPullRequestComments("owner", "repo", 7L)).thenThrow(new IllegalStateException("page failed")); assertThat(skip()).isFalse(); }
    @Test void missingScanReviews() { when(api.listReviewThreadComments("owner", "repo", 7L)).thenReturn(null); assertThat(skip()).isFalse(); }
    @Test void lostLeaseDoesNotSkipOrScan() {
        doThrow(new ReviewRunLeaseService.LeaseLostException(lease)).when(leases).assertHeld(lease);
        assertThatThrownBy(this::skip).isInstanceOf(ReviewRunLeaseService.LeaseLostException.class);
        verifyNoInteractions(api);
    }
    private PrWorkflowContext context(boolean explicit) {
        Bot bot = new Bot(); bot.setId(1L); bot.setUsername("bot");
        WebhookPayload payload = new WebhookPayload();
        WebhookPayload.Repository repo = new WebhookPayload.Repository();
        WebhookPayload.Owner owner = new WebhookPayload.Owner(); owner.setLogin("owner"); repo.setOwner(owner); repo.setName("repo"); payload.setRepository(repo);
        WebhookPayload.PullRequest pr = new WebhookPayload.PullRequest(); pr.setNumber(7L); payload.setPullRequest(pr);
        return new PrWorkflowContext(bot, payload, 1L, (name, log) -> {}, () -> false,
                Map.of(PrWorkflowContext.HINT_EXPLICIT_REVIEW_REQUEST, Boolean.toString(explicit)));
    }
    private void prepareGuard() {
        when(leases.acquire(1L, "owner", "repo", 7L)).thenReturn(lease);
        when(factory.getApiClient(any())).thenReturn(api);
        when(api.getReviewSnapshot("owner", "repo", 7L)).thenReturn(snapshot);
    }
    @Test void skipPostsOneReplyClearsEyesAndReleasesLease() {
        prepareGuard();
        assertThat(service.trySkip(context(false), "review")).isTrue();
        verify(api).postPullRequestComment("owner", "repo", 7L, "No changes since last review at `head-sh`.");
        verify(api).removePullRequestReaction("owner", "repo", 7L, "eyes");
        verify(leases).release(lease);
    }
    @Test void replyFailureReleasesLease() {
        prepareGuard();
        doThrow(new IllegalStateException("write failed")).when(api).postPullRequestComment(any(), any(), any(), any());
        assertThatThrownBy(() -> service.trySkip(context(false), "review")).isInstanceOf(IllegalStateException.class);
        verify(leases).release(lease);
    }
    @Test void explicitGuardDoesNotAcquireLeaseOrFetchSnapshot() {
        assertThat(service.trySkip(context(true), "review")).isFalse();
        verifyNoInteractions(state, leases, factory, api);
    }
}
