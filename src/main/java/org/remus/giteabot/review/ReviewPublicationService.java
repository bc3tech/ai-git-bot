package org.remus.giteabot.review;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.repository.PostReviewAction;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.model.ReviewAnchorComment;
import org.remus.giteabot.repository.model.CommentScan;
import org.remus.giteabot.repository.model.ReviewComment;
import org.remus.giteabot.prworkflow.WorkflowCancelledException;
import org.remus.giteabot.repository.model.ReviewPublicationResult;
import org.remus.giteabot.repository.model.ReviewPublicationResult.CommentOutcome;
import org.remus.giteabot.repository.model.ReviewPublicationResult.Delivery;
import org.remus.giteabot.repository.model.ReviewPublicationResult.Status;
import org.remus.giteabot.repository.model.ReviewSnapshot;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.Collections;
import org.remus.giteabot.audit.PrAuditEvent;
import org.remus.giteabot.audit.PrAuditEventService;
import org.remus.giteabot.review.grounding.GroundingResult;
import org.remus.giteabot.review.grounding.FindingGroundingService;
import org.remus.giteabot.review.state.ReviewFindingRecord;
import org.remus.giteabot.review.state.ReviewRunLeaseService;
import org.remus.giteabot.review.state.ReviewRunLeaseService.LeaseToken;
import org.remus.giteabot.review.state.ReviewStateService;
import java.util.function.UnaryOperator;

/**
 * Publishes a {@link ReviewDocument} for both review workflows: located findings become native
 * inline comments where the provider supports them, everything else stays in a concise summary,
 * and delivery problems are reported without replaying confirmed writes.
 */
@Slf4j
public final class ReviewPublicationService {

    static final String NO_ISSUES = "No issues found.";

    private ReviewPublicationService() {
    }

    /** Independent delivery state of the review's components. */
    public record Outcome(Status inlineStatus, int inlineRequested, int inlineConfirmed,
                          Delivery summary, Delivery action, boolean fallbackUsed, String detail,
                          Map<String, String> remoteIds) {
        public Outcome(Status inlineStatus, int inlineRequested, int inlineConfirmed, Delivery summary,
                       Delivery action, boolean fallbackUsed, String detail) {
            this(inlineStatus, inlineRequested, inlineConfirmed, summary, action, fallbackUsed, detail, Map.of());
        }

        /** True when at least the summary reached the pull request. */
        public boolean delivered() {
            return summary == Delivery.CONFIRMED;
        }

        /** Approval requires a confirmed summary and a publication outcome without unresolved uncertainty. */
        public boolean approvalSafe() {
            return delivered() && (inlineStatus == Status.PUBLISHED || inlineStatus == Status.UNSUPPORTED);
        }
    }

    /**
     * Captures a review snapshot, degrading to a summary-only snapshot of the plain PR diff when the
     * provider cannot produce a consistent one.
     */
    public static ReviewSnapshot snapshot(RepositoryApiClient client, String owner, String repo, Long pr) {
        try {
            ReviewSnapshot snapshot = client.getReviewSnapshot(owner, repo, pr);
            if (snapshot != null) {
                return snapshot;
            }
            log.warn("No review snapshot available for PR #{} in {}/{}; this review will be summary-only",
                    pr, owner, repo);
            return ReviewSnapshot.summaryOnly(client.getPullRequestDiff(owner, repo, pr));
        } catch (RuntimeException e) {
            log.warn("Could not capture a revision-bound snapshot for PR #{} in {}/{}: {}; "
                    + "this review will be summary-only", pr, owner, repo, e.getMessage());
            return ReviewSnapshot.summaryOnly(client.getPullRequestDiff(owner, repo, pr));
        }
    }

    /**
     * @param frame wraps a rendered review body with the workflow's header and footer
     * @param fence checked before every remote write
     */
    public static Outcome publish(RepositoryApiClient client, String owner, String repo, Long pr,
                                  ReviewSnapshot snapshot, ReviewDocument document, PostReviewAction action,
                                  UnaryOperator<String> frame, ReviewFence fence) {
        if (!document.structured()) document = new ReviewDocument(FindingGroundingService.PARSE_FAILURE, List.of(), true);
        PostReviewAction requestedAction = action == null ? PostReviewAction.NONE : action;
        boolean actionRequested = requestedAction != PostReviewAction.NONE;
        List<ReviewDocument.Finding> summaryOnly = new ArrayList<>();
        List<ReviewAnchorComment> anchors = new ArrayList<>();
        Map<ReviewAnchorComment, ReviewDocument.Finding> findingByAnchor = new IdentityHashMap<>();

        ReviewDiffPositionParser.DiffPositions positions = snapshot.inlineSupported()
                ? ReviewDiffPositionParser.parse(snapshot.diff()) : null;
        for (ReviewDocument.Finding finding : document.findings()) {
            Optional<ReviewAnchorComment> anchor = positions != null && finding.hasLocation()
                    ? positions.anchor(finding, inlineBody(finding)) : Optional.empty();
            if (anchor.isPresent()) {
                anchors.add(anchor.get());
                findingByAnchor.put(anchor.get(), finding);
            } else {
                summaryOnly.add(finding);
            }
        }

        if (anchors.isEmpty()) {
            fence.requireActive("before posting review summary");
            client.postReview(owner, repo, pr, frame.apply(render(document.summary(), document.findings(), false)),
                    requestedAction);
            return new Outcome(Status.UNSUPPORTED, 0, 0, Delivery.CONFIRMED,
                    actionRequested ? Delivery.CONFIRMED : null, false, null);
        }

        fence.requireActive("before publishing inline review");
        String body = frame.apply(render(document.summary(), summaryOnly, true));
        ReviewPublicationResult result = client.publishInlineReview(owner, repo, pr, snapshot, body, anchors,
                requestedAction);
        log.info("Inline review publication for PR #{} in {}/{}: {} ({} comment(s) requested)",
                pr, owner, repo, result.status(), anchors.size());

        if (result.status() == Status.STALE_SNAPSHOT) {
            log.warn("Skipping stale review publication for PR #{} in {}/{} at head {}", pr, owner, repo, snapshot.headSha());
            return new Outcome(result.status(), anchors.size(), 0, Delivery.FAILED,
                    actionRequested ? Delivery.FAILED : null, false, result.detail());
        }
        String fullBody = frame.apply(render(document.summary(), document.findings(), false));
        if (result.status() == Status.PENDING_CONFLICT) {
            fence.requireActive("before posting pending-review fallback");
            client.postPullRequestComment(owner, repo, pr, fullBody + "\n\n> ⚠️ "
                    + "Inline comments were not posted because the bot account has a pending review on this "
                    + "pull request. Submit or discard it to restore inline reviews."
                    + (actionRequested ? " The " + describe(requestedAction) + " decision was not submitted." : ""));
            return new Outcome(result.status(), anchors.size(), 0, Delivery.CONFIRMED,
                    actionRequested ? Delivery.FAILED : null, true, result.detail());
        }
        if (result.nothingWritten()) {
            fence.requireActive("before posting summary fallback");
            client.postReview(owner, repo, pr, fullBody, requestedAction);
            return new Outcome(result.status(), anchors.size(), 0, Delivery.CONFIRMED,
                    actionRequested ? Delivery.CONFIRMED : null, true, result.detail());
        }

        List<CommentOutcome> undelivered = result.comments().stream()
                .filter(o -> o.delivery() != Delivery.CONFIRMED).toList();
        int confirmed = anchors.size() - undelivered.size();
        boolean reviewConfirmed = result.review() == Delivery.CONFIRMED;
        Delivery actionDelivery = actionRequested ? result.review() : null;
        if (undelivered.isEmpty() && reviewConfirmed) {
            return new Outcome(result.status(), anchors.size(), confirmed, Delivery.CONFIRMED, actionDelivery,
                    false, null);
        }

        List<ReviewDocument.Finding> resend = new ArrayList<>();
        if (!reviewConfirmed) {
            resend.addAll(summaryOnly);
        }
        undelivered.forEach(o -> resend.add(findingByAnchor.get(o.comment())));
        String supplemental = supplementalBody(document.summary(), resend, undelivered, reviewConfirmed);
        Delivery summaryDelivery = result.review();
        fence.requireActive("before posting delivery-failure summary");
        try {
            client.postReviewComment(owner, repo, pr, frame.apply(supplemental));
            summaryDelivery = Delivery.CONFIRMED;
        } catch (RuntimeException e) {
            log.error("Could not post the delivery-failure summary for PR #{} in {}/{}: {}",
                    pr, owner, repo, e.getMessage());
            if (!reviewConfirmed) {
                throw e;
            }
        }
        return new Outcome(result.status(), anchors.size(), confirmed, summaryDelivery, actionDelivery, true,
                result.detail());
    }

    /** Write-ahead publication for U9/U10. Revision state is the caller's responsibility after successful delivery. */
    public static Outcome publish(RepositoryApiClient client, ReviewSnapshot snapshot, String summary,
                                  GroundingResult grounded, PostReviewAction action, UnaryOperator<String> frame,
                                  ReviewFence fence, ReviewStateService state, ReviewRunLeaseService leases,
                                  LeaseToken lease, String workflowKey, PrAuditEventService audit, PrAuditEvent event) {
        ReviewFence fenced = location -> { fence.requireActive(location); leases.assertHeld(lease); };
        PostReviewAction requested = action == null ? PostReviewAction.NONE : action;
        String notes = String.join("\n\n", grounded.summaryNotes());
        String safeSummary = grounded.summaryNotes().contains(FindingGroundingService.PARSE_FAILURE) ? notes
                : (summary == null ? "" : summary) + (notes.isEmpty() ? "" : "\n\n" + notes);
        Map<ReviewAnchorComment, ReviewDocument.Finding> byAnchor = new IdentityHashMap<>();
        List<ReviewAnchorComment> anchors = new ArrayList<>();
        List<ReviewDocument.Finding> summaryOnly = new ArrayList<>();
        Map<String, ReviewFindingRecord> pending = new LinkedHashMap<>();
        Map<String, String> remoteIds = new LinkedHashMap<>();
        ReviewDiffPositionParser.DiffPositions positions = snapshot.headSha() != null && effectiveBaseSha(snapshot) != null && snapshot.inlineSupported()
                ? ReviewDiffPositionParser.parse(snapshot.diff()) : null;
        try {
            for (ReviewDocument.Finding finding : grounded.publish()) {
                Optional<ReviewAnchorComment> anchor = positions != null && finding.hasLocation()
                        ? positions.anchor(finding, inlineBody(finding)) : Optional.empty();
                if (anchor.isPresent()) {
                    anchors.add(anchor.get());
                    byAnchor.put(anchor.get(), finding);
                } else summaryOnly.add(finding);
                GroundingResult.FindingEvidence evidence = grounded.evidence().get(finding.id());
                ReviewFindingRecord record = new ReviewFindingRecord();
                record.setId(UUID.fromString(finding.id()));
                record.setBotId(lease.botId());
                record.setRepoOwner(lease.owner());
                record.setRepoName(lease.repo());
                record.setPrNumber(lease.prNumber());
                record.setWorkflowKey(workflowKey);
                record.setPath(finding.path());
                record.setSide(finding.side() == null ? null : finding.side().name());
                record.setLine(finding.line());
                record.setQuote(evidence.storedQuote());
                record.setQuoteHash(evidence.quoteHash());
                record.setContextHash(evidence.contextHash());
                record.setSeverity(finding.severity());
                record.setCategory(finding.category());
                record.setBody(finding.body());
                record.setHeadSha(snapshot.headSha());
                record.setSummaryOnly(anchor.isEmpty());
                fenced.requireActive("before recording pending finding");
                state.recordPending(record, lease);
                pending.put(finding.id(), record);
            }
            if (anchors.isEmpty()) {
                fenced.requireActive("before posting grounded summary");
                Optional<String> id = client.postReviewReturningId(lease.owner(), lease.repo(), lease.prNumber(),
                        frame.apply(render(safeSummary, grounded.publish(), false)), requested);
                confirm(grounded.publish(), id.orElse(null), state, lease, grounded, fenced, remoteIds);
                return new Outcome(Status.UNSUPPORTED, 0, 0, Delivery.CONFIRMED,
                        requested == PostReviewAction.NONE ? null : Delivery.CONFIRMED, false, null,
                        Collections.unmodifiableMap(remoteIds));
            }
            fenced.requireActive("before publishing grounded inline review");
            ReviewPublicationResult result = client.publishInlineReview(lease.owner(), lease.repo(), lease.prNumber(), snapshot,
                    frame.apply(render(safeSummary, summaryOnly, true)), anchors, requested);
            if (result.status() == Status.STALE_SNAPSHOT) {
                log.warn("Skipping stale grounded review publication for PR #{} in {}/{} at head {}",
                        lease.prNumber(), lease.owner(), lease.repo(), snapshot.headSha());
                audit.recordGroundingWarning(event, "PUBLICATION", "stale snapshot; findings not published",
                        snapshot.headSha(), effectiveBaseSha(snapshot));
                for (ReviewFindingRecord record : pending.values()) {
                    fenced.requireActive("before discarding stale pending finding");
                    state.discardPending(record.getId(), lease);
                }
                return new Outcome(result.status(), anchors.size(), 0, Delivery.FAILED,
                        requested == PostReviewAction.NONE ? null : Delivery.FAILED, false, result.detail());
            }
            List<ReviewDocument.Finding> resend = new ArrayList<>();
            int confirmed = 0;
            CommentScan<ReviewComment> scan = null;
            if (result.comments().stream().anyMatch(o -> o.delivery() == Delivery.CONFIRMED)) {
                try { scan = client.listReviewThreadComments(lease.owner(), lease.repo(), lease.prNumber()); }
                catch (ReviewRunLeaseService.LeaseLostException | WorkflowCancelledException e) { throw e; }
                catch (RuntimeException ignored) { }
            }
            for (ReviewAnchorComment anchor : anchors) {
                CommentOutcome outcome = result.comments().stream().filter(o -> o.comment() == anchor).findFirst().orElse(null);
                if (outcome != null && outcome.delivery() == Delivery.CONFIRMED) {
                    ReviewDocument.Finding finding = byAnchor.get(anchor);
                    String commentId = outcome.remoteId() != null && outcome.remoteId().matches("[0-9]+") ? outcome.remoteId() : null;
                    String threadId = outcome.remoteId();
                    if (scan != null && scan.complete()) {
                        ReviewComment root = scan.comments().stream()
                                .filter(c -> c.getBody() != null && c.getBody().contains(ReviewFindingRecord.markerFor(UUID.fromString(finding.id()))))
                                .findFirst().orElse(null);
                        if (root != null) {
                            commentId = root.getId() == null ? null : root.getId().toString();
                            threadId = root.getThreadId();
                        }
                    }
                    fenced.requireActive("before recording inline finding");
                    state.markPublished(UUID.fromString(finding.id()), commentId, threadId, lease);
                    remoteIds.put(finding.id(), outcome.remoteId());
                    supersede(finding, state, lease, grounded, fenced);
                    confirmed++;
                } else resend.add(byAnchor.get(anchor));
            }
            if (result.review() == Delivery.CONFIRMED) confirm(summaryOnly, null, state, lease, grounded, fenced, remoteIds);
            else resend.addAll(summaryOnly);
            boolean fallback = !resend.isEmpty() || result.review() != Delivery.CONFIRMED;
            if (fallback) {
                for (ReviewDocument.Finding finding : resend) {
                    ReviewFindingRecord record = pending.get(finding.id());
                    record.setSummaryOnly(true);
                    fenced.requireActive("before recording grounded summary fallback");
                    state.recordPending(record, lease);
                }
                String body = frame.apply(render(safeSummary, resend, false));
                fenced.requireActive("before posting grounded fallback");
                Optional<String> id;
                if (result.status() == Status.PENDING_CONFLICT) {
                    id = client.postPullRequestCommentReturningId(lease.owner(), lease.repo(), lease.prNumber(),
                            body + "\n\n> Inline publication blocked by a pending review; decision was not submitted.");
                } else if (result.nothingWritten()) {
                    id = client.postReviewReturningId(lease.owner(), lease.repo(), lease.prNumber(), body, requested);
                } else {
                    id = client.postReviewCommentReturningId(lease.owner(), lease.repo(), lease.prNumber(),
                            body + "\n\n> Some inline deliveries could not be confirmed and may also appear inline.");
                }
                confirm(resend, id.orElse(null), state, lease, grounded, fenced, remoteIds);
            }
            Delivery decision = requested == PostReviewAction.NONE ? null : result.status() == Status.PENDING_CONFLICT
                    ? Delivery.FAILED : result.nothingWritten() ? Delivery.CONFIRMED : result.review();
            return new Outcome(result.status(), anchors.size(), confirmed, Delivery.CONFIRMED, decision,
                    fallback, result.detail(), Collections.unmodifiableMap(remoteIds));
        } catch (ReviewRunLeaseService.LeaseLostException e) {
            audit.recordGroundingWarning(event, "PUBLICATION", "lease lost", snapshot.headSha(), effectiveBaseSha(snapshot));
            throw e;
        }
    }

    private static String effectiveBaseSha(ReviewSnapshot snapshot) {
        return snapshot.startSha() == null ? snapshot.baseSha() : snapshot.startSha();
    }

    private static void confirm(List<ReviewDocument.Finding> findings, String remoteId, ReviewStateService state,
                                LeaseToken lease, GroundingResult grounded, ReviewFence fence, Map<String, String> ids) {
        for (ReviewDocument.Finding finding : findings) {
            fence.requireActive("before recording published finding");
            state.markPublished(UUID.fromString(finding.id()), remoteId, null, lease);
            ids.put(finding.id(), remoteId);
            supersede(finding, state, lease, grounded, fence);
        }
    }

    private static void supersede(ReviewDocument.Finding finding, ReviewStateService state, LeaseToken lease,
                                  GroundingResult grounded, ReviewFence fence) {
        for (UUID old : grounded.supersedes().getOrDefault(finding.id(), List.of())) {
            fence.requireActive("before recording superseded finding");
            state.markSuperseded(old, lease);
        }
    }

    private static String marker(ReviewDocument.Finding finding) {
        try { return "\n\n" + ReviewFindingRecord.markerFor(UUID.fromString(finding.id())); }
        catch (IllegalArgumentException | NullPointerException e) { return ""; }
    }

    static String inlineBody(ReviewDocument.Finding finding) {
        String label = label(finding);
        return (label.isEmpty() ? finding.body() : label + "\n\n" + finding.body()) + marker(finding);
    }

    /** Renders a summary plus findings (with their locations) as Markdown. */
    public static String render(String summary, List<ReviewDocument.Finding> findings, boolean othersOnly) {
        StringBuilder out = new StringBuilder();
        String text = summary == null ? "" : summary.strip();
        out.append(text.isEmpty() && findings.isEmpty() ? NO_ISSUES : text);
        if (!findings.isEmpty()) {
            if (!out.isEmpty()) {
                out.append("\n\n");
            }
            out.append(othersOnly ? "### Other findings\n\n" : "### Findings\n\n");
            appendList(out, findings);
        }
        return out.toString();
    }

    private static String supplementalBody(String summary, List<ReviewDocument.Finding> findings,
                                           List<CommentOutcome> undelivered, boolean reviewConfirmed) {
        long uncertain = undelivered.stream().filter(o -> o.delivery() == Delivery.UNKNOWN).count();
        long misplaced = undelivered.stream().filter(o -> o.delivery() == Delivery.MISPLACED).count();
        long failed = undelivered.size() - uncertain - misplaced;
        StringBuilder out = new StringBuilder("> ⚠️ ");
        List<String> parts = new ArrayList<>();
        if (failed > 0) {
            parts.add(failed + " inline comment(s) could not be posted");
        }
        if (misplaced > 0) {
            parts.add(misplaced + " inline comment(s) were placed on a different line than intended");
        }
        if (uncertain > 0) {
            parts.add("delivery of " + uncertain + " inline comment(s) could not be confirmed, "
                    + "so they may also appear inline");
        }
        if (!reviewConfirmed) {
            parts.add("the review summary may not have been delivered");
        }
        out.append(String.join("; ", parts)).append(". Their details are included below.\n\n");
        if (!reviewConfirmed && summary != null && !summary.isBlank()) {
            out.append(summary.strip()).append("\n\n");
        }
        if (!findings.isEmpty()) {
            appendList(out, findings);
        }
        return out.toString().strip();
    }

    private static void appendList(StringBuilder out, List<ReviewDocument.Finding> findings) {
        for (ReviewDocument.Finding finding : findings) {
            List<String> prefix = new ArrayList<>();
            String label = label(finding);
            if (!label.isEmpty()) {
                prefix.add(label);
            }
            if (finding.path() != null && !finding.path().isBlank()) {
                prefix.add("`" + finding.path() + (finding.line() != null ? ":" + finding.line() : "") + "`");
            }
            out.append("- ");
            if (!prefix.isEmpty()) {
                out.append(String.join(" ", prefix)).append(" — ");
            }
            out.append(finding.body().strip().replace("\n", "\n  ")).append(marker(finding)).append('\n');
        }
    }

    private static String label(ReviewDocument.Finding finding) {
        StringBuilder label = new StringBuilder();
        if (finding.severity() != null && !finding.severity().isBlank()) {
            label.append("**").append(finding.severity().strip()).append("**");
        }
        if (finding.category() != null && !finding.category().isBlank()) {
            if (!label.isEmpty()) {
                label.append(" · ");
            }
            label.append(finding.category().strip());
        }
        return label.toString();
    }

    private static String describe(PostReviewAction action) {
        return action == PostReviewAction.APPROVE ? "approval" : "request-changes";
    }
}
