package org.remus.giteabot.review.state;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.review.state.ReviewRunLeaseService.LeaseToken;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "review-state.lease-ttl=10s",
        "review-state.lease-wait-max=1200ms"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReviewStateServiceTest {

    @Autowired private ReviewStateService stateService;
    @Autowired private ReviewRunLeaseService leaseService;
    @Autowired private ReviewFindingRepository findingRepository;
    @Autowired private ReviewRevisionRepository revisionRepository;
    @Autowired private ReviewRunLeaseRepository leaseRepository;

    @BeforeEach
    void clearState() {
        findingRepository.deleteAll();
        revisionRepository.deleteAll();
        leaseRepository.deleteAll();
    }

    @Test
    void pendingFindingBecomesPublishedAndIsReturnedAsActivePrior() {
        LeaseToken lease = leaseService.acquire(1L, "owner", "repo", 7L);
        ReviewFindingRecord pending = stateService.recordPending(finding("review"), lease);

        assertThat(pending.getMarker()).isEqualTo(ReviewFindingRecord.markerFor(pending.getId()));
        assertThat(pending.getStatus()).isEqualTo(ReviewFindingStatus.PENDING);

        stateService.markPublished(pending.getId(), "remote-comment", "remote-thread", lease);

        ReviewFindingRecord active = stateService.activeFindings(1L, "owner", "repo", 7L, "review").getFirst();
        assertThat(active.getStatus()).isEqualTo(ReviewFindingStatus.PUBLISHED);
        assertThat(active.getRemoteCommentId()).isEqualTo("remote-comment");
        assertThat(active.getRemoteThreadId()).isEqualTo("remote-thread");
        leaseService.release(lease);
    }

    @Test
    void revisionUpsertUpdatesRevisionAndCommentCursorWithoutClearingCloseMetadata() {
        LeaseToken lease = leaseService.acquire(1L, "owner", "repo", 7L);
        ReviewRevisionRecord revision = revision("review", "head-1", "base-1", false);
        revision.setLastSeenCommentAt(Instant.parse("2026-10-01T12:00:00Z"));
        revision.setLastSeenCommentId("comment-1");
        stateService.upsertRevision(revision, lease);
        Instant closedAt = Instant.parse("2026-10-02T12:00:00Z");
        stateService.markPrClosed(1L, "owner", "repo", 7L, closedAt);

        ReviewRevisionRecord update = revision("review", "head-2", "base-2", true);
        update.setLastSeenCommentAt(Instant.parse("2026-10-03T12:00:00Z"));
        update.setLastSeenCommentId("comment-2");
        ReviewRevisionRecord saved = stateService.upsertRevision(update, lease);

        assertThat(saved.getHeadSha()).isEqualTo("head-2");
        assertThat(saved.getEffectiveBaseSha()).isEqualTo("base-2");
        assertThat(saved.isDegraded()).isTrue();
        assertThat(saved.getLastSeenCommentAt()).isEqualTo(update.getLastSeenCommentAt());
        assertThat(saved.getLastSeenCommentId()).isEqualTo("comment-2");
        assertThat(saved.getPrClosedAt()).isEqualTo(closedAt);

        stateService.markPrReopened(1L, "owner", "repo", 7L);
        assertThat(stateService.findRevision(1L, "owner", "repo", 7L, "review").orElseThrow().getPrClosedAt())
                .isNull();
        leaseService.release(lease);
    }

    @Test
    void workflowsHaveSeparateFindingState_andGoneRemainsForDedupButNotWithdrawal() {
        LeaseToken lease = leaseService.acquire(1L, "owner", "repo", 7L);
        ReviewFindingRecord finding = stateService.recordPending(finding("review"), lease);
        stateService.markPublished(finding.getId(), "remote", null, lease);
        stateService.markGone(finding.getId(), lease);

        assertThat(stateService.activeFindings(1L, "owner", "repo", 7L, "review")).isEmpty();
        assertThat(stateService.findingsForDedup(1L, "owner", "repo", 7L, "review"))
                .extracting(ReviewFindingRecord::getStatus).containsExactly(ReviewFindingStatus.GONE);
        assertThat(stateService.activeFindings(1L, "owner", "repo", 7L, "agentic-review")).isEmpty();
        assertThatThrownBy(() -> stateService.markWithdrawn(finding.getId(), lease))
                .isInstanceOf(IllegalStateException.class);
        leaseService.release(lease);
    }

    @Test
    void staleLeaseRejectsStateWrites_andWithdrawnFindingsAreNotActive() throws Exception {
        LeaseToken stale = leaseService.acquire(1L, "owner", "repo", 7L);
        ReviewRunLease row = leaseRepository.findByBotIdAndRepoOwnerAndRepoNameAndPrNumber(
                1L, "owner", "repo", 7L).orElseThrow();
        row.setLeaseExpiresAt(Instant.now().minusSeconds(1));
        leaseRepository.saveAndFlush(row);
        LeaseToken current = leaseService.acquire(1L, "owner", "repo", 7L);
        assertThat(current.leaseToken()).isGreaterThan(stale.leaseToken());
        assertThatThrownBy(() -> stateService.recordPending(finding("review"), stale))
                .isInstanceOf(ReviewRunLeaseService.LeaseLostException.class);

        ReviewFindingRecord record = stateService.recordPending(finding("review"), current);
        stateService.markPublished(record.getId(), "remote", null, current);
        stateService.markWithdrawn(record.getId(), current);
        assertThat(stateService.activeFindings(1L, "owner", "repo", 7L, "review")).isEmpty();
        leaseService.release(stale);
        leaseService.release(current);
    }

    @Test
    void pendingFindingCanBeDiscardedAfterCompleteMarkerScan() {
        LeaseToken lease = leaseService.acquire(1L, "owner", "repo", 7L);
        ReviewFindingRecord record = stateService.recordPending(finding("review"), lease);
        assertThat(stateService.pendingFindings(1L, "owner", "repo", 7L, "review"))
                .extracting(ReviewFindingRecord::getId).containsExactly(record.getId());

        stateService.discardPending(record.getId(), lease);

        assertThat(findingRepository.existsById(record.getId())).isFalse();
        assertThat(stateService.pendingFindings(1L, "owner", "repo", 7L, "review")).isEmpty();
        leaseService.release(lease);
    }

    @Test
    void secondAcquireWaitsForReleaseAndReceivesHigherFencingToken() throws Exception {
        LeaseToken first = leaseService.acquire(1L, "owner", "repo", 7L);
        CompletableFuture<LeaseToken> waiting = CompletableFuture.supplyAsync(
                () -> leaseService.acquire(1L, "owner", "repo", 7L));
        Thread.sleep(180);
        assertThat(waiting.isDone()).isFalse();

        leaseService.release(first);
        LeaseToken second = waiting.get(2, TimeUnit.SECONDS);

        assertThat(second.leaseToken()).isGreaterThan(first.leaseToken());
        leaseService.release(second);
    }

    private ReviewFindingRecord finding(String workflowKey) {
        ReviewFindingRecord finding = new ReviewFindingRecord();
        finding.setBotId(1L);
        finding.setRepoOwner("owner");
        finding.setRepoName("repo");
        finding.setPrNumber(7L);
        finding.setWorkflowKey(workflowKey);
        finding.setPath("src/App.java");
        finding.setSide("RIGHT");
        finding.setLine(12);
        finding.setQuote("return true;");
        finding.setQuoteHash("quote-hash");
        finding.setContextHash("context-hash");
        finding.setSeverity("HIGH");
        finding.setCategory("BUG");
        finding.setBody("Possible issue");
        finding.setHeadSha("head-1");
        return finding;
    }

    private ReviewRevisionRecord revision(String workflowKey, String head, String base, boolean degraded) {
        ReviewRevisionRecord revision = new ReviewRevisionRecord();
        revision.setBotId(1L);
        revision.setRepoOwner("owner");
        revision.setRepoName("repo");
        revision.setPrNumber(7L);
        revision.setWorkflowKey(workflowKey);
        revision.setHeadSha(head);
        revision.setEffectiveBaseSha(base);
        revision.setReviewedAt(Instant.parse("2026-10-01T10:00:00Z"));
        revision.setDegraded(degraded);
        return revision;
    }
}
