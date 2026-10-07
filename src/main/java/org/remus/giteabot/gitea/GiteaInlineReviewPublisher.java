package org.remus.giteabot.gitea;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.gitea.model.GiteaReview;
import org.remus.giteabot.gitea.model.GiteaReviewComment;
import org.remus.giteabot.repository.model.DiffSide;
import org.remus.giteabot.repository.model.ReviewAnchorComment;
import org.remus.giteabot.repository.model.ReviewPublicationResult;
import org.remus.giteabot.repository.model.ReviewPublicationResult.CommentOutcome;
import org.remus.giteabot.repository.model.ReviewPublicationResult.Delivery;
import org.remus.giteabot.repository.model.ReviewPublicationResult.Status;
import org.remus.giteabot.repository.model.ReviewSnapshot;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Best-effort, revision-bound inline review publication for Gitea.
 *
 * <p>Gitea has no atomic "create review at commit X or fail" primitive: a review POST silently
 * adds its comments to any pending review the bot account already owns, and positions are not
 * rejected when the head moves. This class therefore refuses to write when a pending review
 * exists, rechecks the head and merge base immediately before the single POST, and reads the
 * created comments back to report anything Gitea placed differently. It never retries the POST.</p>
 */
@Slf4j
final class GiteaInlineReviewPublisher {

    private static final int PAGE_SIZE = 50;
    private static final int MAX_PAGES = 20;

    private final RestClient rest;

    GiteaInlineReviewPublisher(RestClient rest) {
        this.rest = rest;
    }

    ReviewSnapshot snapshot(GiteaApiClient client, String owner, String repo, Long pr) {
        String diff = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            Revisions before = revisions(client.getPullRequestDetails(owner, repo, pr));
            diff = client.getPullRequestDiff(owner, repo, pr);
            Revisions after = revisions(client.getPullRequestDetails(owner, repo, pr));
            if (before.complete() && before.equals(after)) {
                return new ReviewSnapshot(before.head(), before.base(), before.mergeBase(), diff, true);
            }
            log.info("PR #{} in {}/{} moved while its diff was read; retrying snapshot", pr, owner, repo);
        }
        log.warn("PR #{} in {}/{} kept moving; this review will be summary-only", pr, owner, repo);
        return ReviewSnapshot.summaryOnly(diff);
    }

    ReviewPublicationResult publish(GiteaApiClient client, String owner, String repo, Long pr,
                                    ReviewSnapshot snapshot, String body, List<ReviewAnchorComment> comments,
                                    String event) {
        if (snapshot == null || !snapshot.inlineSupported() || comments == null || comments.isEmpty()) {
            return ReviewPublicationResult.unsupported();
        }
        Set<Long> existingIds = new HashSet<>();
        try {
            List<GiteaReview> existing = listReviews(owner, repo, pr);
            if (existing.stream().anyMatch(r -> "PENDING".equalsIgnoreCase(r.getState()))) {
                return ReviewPublicationResult.notWritten(Status.PENDING_CONFLICT, comments,
                        "The bot account has a pending Gitea review on this pull request; "
                                + "submitting would merge into it");
            }
            existing.forEach(r -> existingIds.add(r.getId()));
            Revisions current = revisions(client.getPullRequestDetails(owner, repo, pr));
            if (!snapshot.headSha().equals(current.head())
                    || !Objects.equals(expectedBase(snapshot), current.diffBase())) {
                return ReviewPublicationResult.notWritten(Status.STALE_SNAPSHOT, comments,
                        "Pull request head or base changed after the reviewed diff was read");
            }
        } catch (RestClientException e) {
            return ReviewPublicationResult.notWritten(Status.REJECTED, comments,
                    "Could not verify pull request state before publishing: " + e.getMessage());
        }

        InlinePublishRequest request = new InlinePublishRequest(body, event, snapshot.headSha(),
                comments.stream().map(GiteaInlineReviewPublisher::toRequest).toList());
        log.info("Posting {} review with {} inline comment(s) on PR #{} in {}/{} at {}",
                event, comments.size(), pr, owner, repo, snapshot.headSha());
        try {
            GiteaReview created = rest.post()
                    .uri("/api/v1/repos/{owner}/{repo}/pulls/{index}/reviews", owner, repo, pr)
                    .body(request)
                    .retrieve()
                    .body(GiteaReview.class);
            Long reviewId = created != null ? created.getId() : null;
            if (reviewId == null) {
                Optional<Long> found = findCreated(owner, repo, pr, existingIds, body);
                if (found.isEmpty()) {
                    return ReviewPublicationResult.unknown(comments,
                            "Gitea accepted the review but its ID could not be determined");
                }
                reviewId = found.get();
            }
            return verify(owner, repo, pr, reviewId, comments);
        } catch (HttpClientErrorException e) {
            return afterFailure(owner, repo, pr, existingIds, body, comments, true,
                    "Gitea rejected the inline review (" + e.getStatusCode().value() + ")");
        } catch (RestClientException e) {
            return afterFailure(owner, repo, pr, existingIds, body, comments, false,
                    "Inline review outcome unknown: " + e.getMessage());
        }
    }

    private ReviewPublicationResult afterFailure(String owner, String repo, Long pr, Set<Long> existingIds,
                                                 String body, List<ReviewAnchorComment> comments,
                                                 boolean clientError, String detail) {
        Optional<Long> found;
        try {
            found = findCreated(owner, repo, pr, existingIds, body);
        } catch (RestClientException readBackFailure) {
            log.warn("{}; read-back also failed: {}", detail, readBackFailure.getMessage());
            return ReviewPublicationResult.unknown(comments, detail);
        }
        if (found.isPresent()) {
            log.warn("{}, but a matching review was created; verifying it", detail);
            return verify(owner, repo, pr, found.get(), comments);
        }
        log.warn(detail);
        return clientError
                ? ReviewPublicationResult.notWritten(Status.REJECTED, comments, detail)
                : ReviewPublicationResult.unknown(comments, detail);
    }

    private ReviewPublicationResult verify(String owner, String repo, Long pr, long reviewId,
                                           List<ReviewAnchorComment> requested) {
        List<GiteaReviewComment> remote;
        try {
            remote = rest.get()
                    .uri("/api/v1/repos/{owner}/{repo}/pulls/{index}/reviews/{id}/comments",
                            owner, repo, pr, reviewId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<GiteaReviewComment>>() {});
        } catch (RestClientException e) {
            List<CommentOutcome> unknown = requested.stream()
                    .map(c -> new CommentOutcome(c, Delivery.UNKNOWN, null)).toList();
            return new ReviewPublicationResult(Status.UNKNOWN, Delivery.CONFIRMED, unknown,
                    "Review " + reviewId + " was created but its comments could not be read back");
        }
        List<GiteaReviewComment> unmatched = new ArrayList<>(remote != null ? remote : List.of());
        List<CommentOutcome> outcomes = new ArrayList<>();
        for (ReviewAnchorComment comment : requested) {
            GiteaReviewComment match = takeMatch(unmatched, comment);
            if (match == null) {
                outcomes.add(new CommentOutcome(comment, Delivery.FAILED, null));
            } else {
                Integer actual = comment.side() == DiffSide.NEW ? match.getPosition() : match.getOriginalPosition();
                Delivery delivery = actual != null && actual == comment.line() ? Delivery.CONFIRMED : Delivery.MISPLACED;
                outcomes.add(new CommentOutcome(comment, delivery, String.valueOf(match.getId())));
            }
        }
        boolean allConfirmed = outcomes.stream().allMatch(o -> o.delivery() == Delivery.CONFIRMED);
        long misplaced = outcomes.stream().filter(o -> o.delivery() == Delivery.MISPLACED).count();
        long missing = outcomes.stream().filter(o -> o.delivery() == Delivery.FAILED).count();
        String detail = allConfirmed ? null
                : "Gitea review " + reviewId + ": " + misplaced + " misplaced, " + missing + " missing comment(s)";
        if (detail != null) {
            log.warn(detail);
        }
        return new ReviewPublicationResult(allConfirmed ? Status.PUBLISHED : Status.PARTIAL,
                Delivery.CONFIRMED, outcomes, detail);
    }

    private static GiteaReviewComment takeMatch(List<GiteaReviewComment> remote, ReviewAnchorComment wanted) {
        String wantedBody = normalize(wanted.body());
        for (int i = 0; i < remote.size(); i++) {
            GiteaReviewComment candidate = remote.get(i);
            boolean pathMatches = Objects.equals(candidate.getPath(), wanted.path())
                    || Objects.equals(candidate.getPath(), wanted.oldPath());
            if (pathMatches && wantedBody.equals(normalize(candidate.getBody()))) {
                return remote.remove(i);
            }
        }
        return null;
    }

    private Optional<Long> findCreated(String owner, String repo, Long pr, Set<Long> existingIds, String body) {
        String wanted = normalize(body);
        return listReviews(owner, repo, pr).stream()
                .filter(r -> r.getId() != null && !existingIds.contains(r.getId()))
                .filter(r -> wanted.equals(normalize(r.getBody())))
                .map(GiteaReview::getId)
                .reduce((first, second) -> second);
    }

    private List<GiteaReview> listReviews(String owner, String repo, Long pr) {
        List<GiteaReview> all = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            int current = page;
            List<GiteaReview> batch = rest.get()
                    .uri(b -> b.path("/api/v1/repos/{owner}/{repo}/pulls/{index}/reviews")
                            .queryParam("page", current).queryParam("limit", PAGE_SIZE)
                            .build(owner, repo, pr))
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<GiteaReview>>() {});
            if (batch == null || batch.isEmpty()) {
                break;
            }
            all.addAll(batch);
            if (batch.size() < PAGE_SIZE) {
                break;
            }
        }
        return all;
    }

    private static String expectedBase(ReviewSnapshot snapshot) {
        return snapshot.startSha() != null ? snapshot.startSha() : snapshot.baseSha();
    }

    private static InlineComment toRequest(ReviewAnchorComment comment) {
        boolean newSide = comment.side() == DiffSide.NEW;
        return new InlineComment(comment.path(), comment.body(),
                newSide ? null : comment.line(), newSide ? comment.line() : null);
    }

    private static String normalize(String text) {
        return text == null ? "" : text.replace("\r\n", "\n").strip();
    }

    @SuppressWarnings("unchecked")
    private static Revisions revisions(Map<String, Object> details) {
        String head = details.get("head") instanceof Map<?, ?> h ? sha(((Map<String, Object>) h).get("sha")) : null;
        String base = details.get("base") instanceof Map<?, ?> b ? sha(((Map<String, Object>) b).get("sha")) : null;
        return new Revisions(head, base, sha(details.get("merge_base")));
    }

    private static String sha(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    /** Head, base-branch tip and merge base of a pull request; the diff is computed against the merge base. */
    private record Revisions(String head, String base, String mergeBase) {
        boolean complete() {
            return head != null && (base != null || mergeBase != null);
        }

        String diffBase() {
            return mergeBase != null ? mergeBase : base;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record InlinePublishRequest(String body, String event, @JsonProperty("commit_id") String commitId,
                                List<InlineComment> comments) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record InlineComment(String path, String body,
                         @JsonProperty("old_position") Integer oldPosition,
                         @JsonProperty("new_position") Integer newPosition) {}
}
