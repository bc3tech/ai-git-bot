package org.remus.giteabot.review;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.GiteaClientFactory;
import org.remus.giteabot.prworkflow.PrWorkflowContext;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.model.ReviewSnapshot;
import org.remus.giteabot.review.grounding.PriorFindingsDigest.CommentCursor;
import org.remus.giteabot.review.grounding.PriorFindingsService;
import org.remus.giteabot.review.state.ReviewRevisionRecord;
import org.remus.giteabot.review.state.ReviewRunLeaseService;
import org.remus.giteabot.review.state.ReviewRunLeaseService.LeaseToken;
import org.remus.giteabot.review.state.ReviewStateService;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ReviewSkipService {

    private final ReviewStateService stateService;
    private final ReviewRunLeaseService leaseService;
    private final GiteaClientFactory clientFactory;

    public ReviewSkipService(ReviewStateService stateService, ReviewRunLeaseService leaseService,
                             GiteaClientFactory clientFactory) {
        this.stateService = stateService;
        this.leaseService = leaseService;
        this.clientFactory = clientFactory;
    }

    /** Entry-point guard until the review services own the full-run lease. */
    public boolean trySkip(PrWorkflowContext context, String workflowKey) {
        if (!context.explicitRequest()) {
            return false;
        }
        String owner = context.payload().getRepository().getOwner().getLogin();
        String repo = context.payload().getRepository().getName();
        Long pr = context.payload().getPullRequest().getNumber();
        Long botId = context.bot().getId();
        if (stateService.findRevision(botId, owner, repo, pr, workflowKey).isEmpty()) {
            return false;
        }
        LeaseToken lease = leaseService.acquire(botId, owner, repo, pr);
        try {
            context.requireActive("before checking unchanged review revision");
            RepositoryApiClient api = clientFactory.getApiClient(context.bot().getGitIntegration());
            ReviewSnapshot snapshot = ReviewPublicationService.snapshot(api, owner, repo, pr);
            if (!shouldSkip(api, lease, workflowKey, context.bot().getUsername(), snapshot, context.explicitRequest())) {
                return false;
            }
            context.requireActive("before posting unchanged revision reply");
            leaseService.assertHeld(lease);
            String message = "No changes since last review at `"
                    + snapshot.headSha().substring(0, Math.min(7, snapshot.headSha().length())) + "`.";
            api.postPullRequestComment(owner, repo, pr, message);
            leaseService.assertHeld(lease);
            try {
                api.removePullRequestReaction(owner, repo, pr, "eyes");
            } catch (RuntimeException e) {
                log.warn("Could not clear unchanged-review acknowledgement on PR #{}: {}", pr, e.getMessage());
            }
            if (context.payload().getComment() != null && context.payload().getComment().getId() != null) {
                leaseService.assertHeld(lease);
                try {
                    api.removeReaction(owner, repo, context.payload().getComment().getId(), "eyes");
                } catch (RuntimeException e) {
                    log.warn("Could not clear unchanged-review comment acknowledgement on PR #{}: {}", pr, e.getMessage());
                }
            }
            context.appendStep("review-skip", message);
            return true;
        } finally {
            leaseService.release(lease);
        }
    }

    /** Call after acquiring the run lease and reading the snapshot; automatic requests always run. */
    public boolean shouldSkip(RepositoryApiClient api, LeaseToken lease, String workflowKey, String botLogin,
                              ReviewSnapshot snapshot, boolean explicitRequest) {
        if (!explicitRequest || snapshot == null || !snapshot.pinned()) {
            return false;
        }
        leaseService.assertHeld(lease);
        ReviewRevisionRecord previous = stateService.findRevision(lease.botId(), lease.owner(), lease.repo(),
                lease.prNumber(), workflowKey).orElse(null);
        if (previous == null || previous.isDegraded() || previous.getReviewedAt() == null
                || !snapshot.headSha().equals(previous.getHeadSha())
                || !snapshot.effectiveBaseSha().equals(previous.getEffectiveBaseSha())) {
            return false;
        }
        CommentCursor cursor = new CommentCursor(previous.getLastSeenCommentAt(), previous.getLastSeenCommentId());
        return !PriorFindingsService.hasNewComments(api, lease, botLogin, cursor);
    }
}
