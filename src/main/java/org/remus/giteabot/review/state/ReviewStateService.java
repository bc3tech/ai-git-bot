package org.remus.giteabot.review.state;

import org.remus.giteabot.review.state.ReviewRunLeaseService.LeaseToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ReviewStateService {

    private static final List<ReviewFindingStatus> ACTIVE_STATUSES = List.of(ReviewFindingStatus.PUBLISHED);
    private static final List<ReviewFindingStatus> DEDUP_STATUSES =
            List.of(ReviewFindingStatus.PUBLISHED, ReviewFindingStatus.GONE);

    private final ReviewFindingRepository findingRepository;
    private final ReviewRevisionRepository revisionRepository;
    private final ReviewRunLeaseService leaseService;

    public ReviewStateService(ReviewFindingRepository findingRepository,
                              ReviewRevisionRepository revisionRepository,
                              ReviewRunLeaseService leaseService) {
        this.findingRepository = findingRepository;
        this.revisionRepository = revisionRepository;
        this.leaseService = leaseService;
    }

    @Transactional
    public ReviewFindingRecord recordPending(ReviewFindingRecord finding, LeaseToken lease) {
        leaseService.assertHeld(lease);
        requireLeaseScope(finding.getBotId(), finding.getRepoOwner(), finding.getRepoName(), finding.getPrNumber(), lease);
        if (finding.getStatus() != null && finding.getStatus() != ReviewFindingStatus.PENDING) {
            throw new IllegalArgumentException("A new finding must start in PENDING status");
        }
        if (finding.getId() == null) {
            finding.setId(UUID.randomUUID());
        }
        finding.setMarker(ReviewFindingRecord.markerFor(finding.getId()));
        finding.setStatus(ReviewFindingStatus.PENDING);
        return findingRepository.save(finding);
    }

    @Transactional
    public ReviewFindingRecord markPublished(UUID findingId, String remoteCommentId,
                                             String remoteThreadId, LeaseToken lease) {
        leaseService.assertHeld(lease);
        ReviewFindingRecord finding = requireFinding(findingId, lease);
        if (finding.getStatus() != ReviewFindingStatus.PENDING
                && finding.getStatus() != ReviewFindingStatus.PUBLISHED) {
            throw new IllegalStateException("Only pending findings can be published");
        }
        finding.setStatus(ReviewFindingStatus.PUBLISHED);
        finding.setRemoteCommentId(remoteCommentId);
        finding.setRemoteThreadId(remoteThreadId);
        return findingRepository.save(finding);
    }

    @Transactional
    public void markWithdrawn(UUID findingId, LeaseToken lease) {
        leaseService.assertHeld(lease);
        ReviewFindingRecord finding = requireFinding(findingId, lease);
        if (finding.getStatus() == ReviewFindingStatus.WITHDRAWN) {
            return;
        }
        requireStatus(finding, ReviewFindingStatus.PUBLISHED);
        finding.setStatus(ReviewFindingStatus.WITHDRAWN);
        findingRepository.save(finding);
    }

    @Transactional
    public void markSuperseded(UUID findingId, LeaseToken lease) {
        updatePublishedStatus(findingId, ReviewFindingStatus.SUPERSEDED, lease);
    }

    @Transactional
    public void markGone(UUID findingId, LeaseToken lease) {
        updatePublishedStatus(findingId, ReviewFindingStatus.GONE, lease);
    }

    @Transactional
    public void discardPending(UUID findingId, LeaseToken lease) {
        leaseService.assertHeld(lease);
        findingRepository.findById(findingId).ifPresent(finding -> {
            requireLeaseScope(finding.getBotId(), finding.getRepoOwner(), finding.getRepoName(), finding.getPrNumber(), lease);
            if (finding.getStatus() == ReviewFindingStatus.PENDING) {
                findingRepository.delete(finding);
            }
        });
    }

    @Transactional
    public void updateLastAnsweredCommentId(UUID findingId, String commentId, LeaseToken lease) {
        leaseService.assertHeld(lease);
        ReviewFindingRecord finding = requireFinding(findingId, lease);
        requireStatus(finding, ReviewFindingStatus.PUBLISHED);
        finding.setLastAnsweredCommentId(commentId);
        findingRepository.save(finding);
    }

    @Transactional(readOnly = true)
    public List<ReviewFindingRecord> activeFindings(Long botId, String owner, String repo,
                                                     Long prNumber, String workflowKey) {
        return findingsWithStatuses(botId, owner, repo, prNumber, workflowKey, ACTIVE_STATUSES);
    }

    @Transactional(readOnly = true)
    public List<ReviewFindingRecord> pendingFindings(Long botId, String owner, String repo,
                                                      Long prNumber, String workflowKey) {
        return findingsWithStatuses(botId, owner, repo, prNumber, workflowKey,
                List.of(ReviewFindingStatus.PENDING));
    }

    @Transactional(readOnly = true)
    public List<ReviewFindingRecord> findingsForDedup(Long botId, String owner, String repo,
                                                       Long prNumber, String workflowKey) {
        return findingsWithStatuses(botId, owner, repo, prNumber, workflowKey, DEDUP_STATUSES);
    }

    @Transactional(readOnly = true)
    public Optional<ReviewRevisionRecord> findRevision(Long botId, String owner, String repo,
                                                        Long prNumber, String workflowKey) {
        return revisionRepository.findByBotIdAndRepoOwnerAndRepoNameAndPrNumberAndWorkflowKey(
                botId, owner, repo, prNumber, workflowKey);
    }

    @Transactional
    public ReviewRevisionRecord upsertRevision(ReviewRevisionRecord revision, LeaseToken lease) {
        leaseService.assertHeld(lease);
        if (!lease.botId().equals(revision.getBotId()) || !lease.owner().equals(revision.getRepoOwner())
                || !lease.repo().equals(revision.getRepoName()) || !lease.prNumber().equals(revision.getPrNumber())) {
            throw new IllegalArgumentException("Revision scope does not match the held PR lease");
        }
        ReviewRevisionRecord stored = revisionRepository
                .findByBotIdAndRepoOwnerAndRepoNameAndPrNumberAndWorkflowKey(
                        revision.getBotId(), revision.getRepoOwner(), revision.getRepoName(),
                        revision.getPrNumber(), revision.getWorkflowKey())
                .orElseGet(ReviewRevisionRecord::new);
        Instant prClosedAt = stored.getPrClosedAt();
        stored.setBotId(revision.getBotId());
        stored.setRepoOwner(revision.getRepoOwner());
        stored.setRepoName(revision.getRepoName());
        stored.setPrNumber(revision.getPrNumber());
        stored.setWorkflowKey(revision.getWorkflowKey());
        stored.setHeadSha(revision.getHeadSha());
        stored.setEffectiveBaseSha(revision.getEffectiveBaseSha());
        stored.setReviewedAt(revision.getReviewedAt());
        stored.setDegraded(revision.isDegraded());
        stored.setLastSeenCommentAt(revision.getLastSeenCommentAt());
        stored.setLastSeenCommentId(revision.getLastSeenCommentId());
        stored.setPrClosedAt(revision.getPrClosedAt() != null ? revision.getPrClosedAt() : prClosedAt);
        return revisionRepository.save(stored);
    }

    /** PR lifecycle metadata is updated by webhook events, outside review-run writes. */
    @Transactional
    public int markPrClosed(Long botId, String owner, String repo, Long prNumber, Instant closedAt) {
        return revisionRepository.stampPrClosed(botId, owner, repo, prNumber, closedAt);
    }

    /** A reopen starts a fresh operational retention window for this PR. */
    @Transactional
    public int markPrReopened(Long botId, String owner, String repo, Long prNumber) {
        return revisionRepository.clearPrClosed(botId, owner, repo, prNumber);
    }

    private List<ReviewFindingRecord> findingsWithStatuses(Long botId, String owner, String repo,
                                                            Long prNumber, String workflowKey,
                                                            List<ReviewFindingStatus> statuses) {
        return findingRepository.findByBotIdAndRepoOwnerAndRepoNameAndPrNumberAndWorkflowKeyAndStatusInOrderByCreatedAtAsc(
                botId, owner, repo, prNumber, workflowKey, statuses);
    }

    private void updatePublishedStatus(UUID findingId, ReviewFindingStatus status, LeaseToken lease) {
        leaseService.assertHeld(lease);
        ReviewFindingRecord finding = requireFinding(findingId, lease);
        requireStatus(finding, ReviewFindingStatus.PUBLISHED);
        finding.setStatus(status);
        findingRepository.save(finding);
    }

    private ReviewFindingRecord requireFinding(UUID findingId, LeaseToken lease) {
        ReviewFindingRecord finding = findingRepository.findById(findingId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown review finding: " + findingId));
        requireLeaseScope(finding.getBotId(), finding.getRepoOwner(), finding.getRepoName(), finding.getPrNumber(), lease);
        return finding;
    }

    private static void requireLeaseScope(Long botId, String owner, String repo, Long prNumber, LeaseToken lease) {
        if (!lease.botId().equals(botId) || !lease.owner().equals(owner)
                || !lease.repo().equals(repo) || !lease.prNumber().equals(prNumber)) {
            throw new IllegalArgumentException("Finding scope does not match the held PR lease");
        }
    }

    private static void requireStatus(ReviewFindingRecord finding, ReviewFindingStatus expected) {
        if (finding.getStatus() != expected) {
            throw new IllegalStateException("Expected finding status " + expected + " but was " + finding.getStatus());
        }
    }
}
