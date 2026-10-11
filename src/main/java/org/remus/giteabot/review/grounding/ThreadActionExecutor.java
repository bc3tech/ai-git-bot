package org.remus.giteabot.review.grounding;

import org.remus.giteabot.audit.AuditEventType;
import org.remus.giteabot.audit.PrAuditEvent;
import org.remus.giteabot.audit.PrAuditEventService;
import org.remus.giteabot.prworkflow.WorkflowCancelledException;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.model.CommentScan;
import org.remus.giteabot.repository.model.ReviewComment;
import org.remus.giteabot.repository.model.ThreadCapabilities;
import org.remus.giteabot.review.ReviewFence;
import org.remus.giteabot.review.state.ReviewFindingRecord;
import org.remus.giteabot.review.state.ReviewFindingStatus;
import org.remus.giteabot.review.state.ReviewRunLeaseService;
import org.remus.giteabot.review.state.ReviewRunLeaseService.LeaseToken;
import org.remus.giteabot.review.state.ReviewRunLeaseService.LeaseLostException;
import org.remus.giteabot.review.state.ReviewStateService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ThreadActionExecutor {
    private final ReviewStateService state;
    private final ReviewRunLeaseService leases;
    private final PrAuditEventService audit;

    public ThreadActionExecutor(ReviewStateService state, ReviewRunLeaseService leases, PrAuditEventService audit) {
        this.state = state;
        this.leases = leases;
        this.audit = audit;
    }

    /** Failed actions leave priors active; callers must mark the revision degraded so explicit requests retry them. */
    public List<ThreadAction> execute(RepositoryApiClient api, LeaseToken lease, List<ThreadAction> actions,
                                      String botLogin, PrAuditEvent event, String headSha, String baseSha,
                                      ReviewFence fence) {
        if (headSha == null || baseSha == null) return List.of();
        List<ThreadAction> failed = new ArrayList<>();
        try {
            for (ThreadAction action : actions) {
                ReviewFindingRecord prior = action.prior().finding();
                if (prior.getStatus() != ReviewFindingStatus.PUBLISHED) continue;
                ThreadCapabilities capabilities = api.threadCapabilities();
                String thread = prior.getRemoteThreadId() == null ? prior.getRemoteCommentId() : prior.getRemoteThreadId();
                String text = (action instanceof ThreadAction.Withdraw ? "Withdrawn: " : "Finding upheld: ")
                        + action.reason() + "\n\n" + prior.getMarker();
                try {
                    check(lease, fence, "before replying to prior finding");
                    if (!prior.isSummaryOnly() && thread != null && capabilities.canReplyInThread()) {
                        try { api.replyToReviewThread(lease.owner(), lease.repo(), lease.prNumber(), thread, text); }
                        catch (UnsupportedOperationException e) { linked(api, lease, prior, text, fence); }
                        catch (org.springframework.web.client.RestClientResponseException e) {
                            if (e.getStatusCode().value() != 404) throw e;
                            linked(api, lease, prior, text, fence);
                        }
                    } else linked(api, lease, prior, text, fence);
                } catch (LeaseLostException | WorkflowCancelledException e) { throw e; }
                catch (RuntimeException e) {
                    failed.add(action);
                    audit.recordGroundingWarning(event, "THREAD", "Thread action failed; prior left active for retry", headSha, baseSha);
                    continue;
                }
                if (action instanceof ThreadAction.Withdraw) {
                    if (!prior.isSummaryOnly() && capabilities.canEditComment() && prior.getRemoteCommentId() != null) {
                        check(lease, fence, "before editing withdrawn finding");
                        try { api.editReviewComment(lease.owner(), lease.repo(), lease.prNumber(), prior.getRemoteCommentId(),
                                "**Withdrawn** — " + action.reason() + "\n\n" + prior.getBody() + "\n\n" + prior.getMarker()); }
                        catch (LeaseLostException | WorkflowCancelledException e) { throw e; }
                        catch (RuntimeException ignored) { }
                    }
                    if (!prior.isSummaryOnly() && thread != null && capabilities.canResolveThread()) {
                        boolean safe = false;
                        try { safe = noNewReplies(api, lease, action.prior(), botLogin); }
                        catch (LeaseLostException | WorkflowCancelledException e) { throw e; }
                        catch (RuntimeException ignored) { }
                        if (safe) {
                            check(lease, fence, "before resolving withdrawn finding");
                            try { api.resolveReviewThread(lease.owner(), lease.repo(), lease.prNumber(), thread); }
                            catch (LeaseLostException | WorkflowCancelledException e) { throw e; }
                            catch (RuntimeException ignored) { }
                        }
                    }
                    check(lease, fence, "before recording withdrawal");
                    state.markWithdrawn(prior.getId(), lease);
                    audit.recordFindingDisposition(event, AuditEventType.FINDING_WITHDRAWN, prior.getId().toString(),
                            prior.getPath(), prior.getSide(), prior.getLine(), prior.getQuote(), prior.getSeverity(),
                            prior.getCategory(), "THREAD", action.reason(), headSha, baseSha);
                } else {
                    check(lease, fence, "before recording answered dispute");
                    state.updateLastAnsweredCommentId(prior.getId(), action.prior().latestCommentId(), lease);
                }
            }
        } catch (LeaseLostException e) {
            audit.recordGroundingWarning(event, "THREAD", "lease lost", headSha, baseSha);
            throw e;
        }
        return List.copyOf(failed);
    }

    private void linked(RepositoryApiClient api, LeaseToken lease, ReviewFindingRecord prior, String text, ReviewFence fence) {
        String link = prior.getRemoteCommentId() == null ? null : api.pullRequestCommentLink(lease.owner(), lease.repo(),
                lease.prNumber(), prior.getRemoteCommentId()).orElse(null);
        check(lease, fence, "before posting linked finding reply");
        api.postPullRequestCommentReturningId(lease.owner(), lease.repo(), lease.prNumber(),
                text + (link == null ? "" : "\n\n[Original finding](" + link + ")"));
    }

    private boolean noNewReplies(RepositoryApiClient api, LeaseToken lease, PriorFindingsDigest.PriorFinding prior,
                                 String botLogin) {
        if (botLogin == null || botLogin.isBlank()) return false;
        CommentScan<ReviewComment> scan = api.listReviewThreadComments(lease.owner(), lease.repo(), lease.prNumber());
        if (!scan.complete()) return false;
        Set<String> seen = prior.replies().stream().map(PriorFindingsDigest.Reply::id)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        ReviewFindingRecord record = prior.finding();
        return scan.comments().stream().filter(c -> !botLogin.equalsIgnoreCase(c.getUserLogin()))
                .filter(c -> record.getRemoteThreadId() != null && record.getRemoteThreadId().equals(c.getThreadId())
                        || record.getRemoteCommentId() != null && record.getRemoteCommentId().equals(c.getParentId()))
                .allMatch(c -> c.getId() != null && seen.contains(c.getId().toString()));
    }

    private void check(LeaseToken lease, ReviewFence fence, String location) {
        fence.requireActive(location);
        leases.assertHeld(lease);
    }
}
