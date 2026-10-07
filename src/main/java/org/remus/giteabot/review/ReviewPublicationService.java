package org.remus.giteabot.review;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.repository.PostReviewAction;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.model.ReviewAnchorComment;
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
                          Delivery summary, Delivery action, boolean fallbackUsed, String detail) {

        /** True when at least the summary reached the pull request. */
        public boolean delivered() {
            return summary == Delivery.CONFIRMED;
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
            client.postPullRequestComment(owner, repo, pr, frame.apply(supplemental));
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

    static String inlineBody(ReviewDocument.Finding finding) {
        String label = label(finding);
        return label.isEmpty() ? finding.body() : label + "\n\n" + finding.body();
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
            out.append(finding.body().strip().replace("\n", "\n  ")).append('\n');
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
