package org.remus.giteabot.repository.model;

import java.util.List;

/**
 * Outcome of {@code RepositoryApiClient#publishInlineReview}. Each component is tracked
 * independently so callers never mistake a partial or uncertain delivery for success.
 *
 * @param status   overall outcome
 * @param review   delivery state of the review body and its formal action
 * @param comments per-comment delivery outcomes, in request order
 * @param detail   operator-facing explanation for non-successful outcomes
 */
public record ReviewPublicationResult(Status status, Delivery review, List<CommentOutcome> comments,
                                      String detail) {

    public enum Status {
        /** Review and every inline comment were confirmed. */
        PUBLISHED,
        /** Something was delivered but at least one component failed, is unknown, or was misplaced. */
        PARTIAL,
        /** Nothing was written because the pull request moved past the reviewed snapshot. */
        STALE_SNAPSHOT,
        /** Nothing was written because an unrelated pending review would have been consumed. */
        PENDING_CONFLICT,
        /** The provider does not support revision-bound inline publication. Nothing was written. */
        UNSUPPORTED,
        /** The provider definitely rejected the publication. Nothing was written. */
        REJECTED,
        /** The outcome could not be determined (e.g. lost response). Writes may exist remotely. */
        UNKNOWN
    }

    public enum Delivery {
        CONFIRMED,
        FAILED,
        UNKNOWN,
        /** Delivered, but read-back found it attached to a different location than requested. */
        MISPLACED
    }

    public record CommentOutcome(ReviewAnchorComment comment, Delivery delivery, String remoteId) {}

    public ReviewPublicationResult {
        comments = comments == null ? List.of() : List.copyOf(comments);
    }

    /** True when nothing reached the provider, so the caller may safely publish an alternative. */
    public boolean nothingWritten() {
        return switch (status) {
            case STALE_SNAPSHOT, PENDING_CONFLICT, UNSUPPORTED, REJECTED -> true;
            default -> false;
        };
    }

    public static ReviewPublicationResult unsupported() {
        return new ReviewPublicationResult(Status.UNSUPPORTED, Delivery.FAILED, List.of(),
                "Provider does not support revision-bound inline review comments");
    }

    public static ReviewPublicationResult notWritten(Status status, List<ReviewAnchorComment> requested,
                                                     String detail) {
        return new ReviewPublicationResult(status, Delivery.FAILED,
                requested.stream().map(c -> new CommentOutcome(c, Delivery.FAILED, null)).toList(), detail);
    }

    public static ReviewPublicationResult unknown(List<ReviewAnchorComment> requested, String detail) {
        return new ReviewPublicationResult(Status.UNKNOWN, Delivery.UNKNOWN,
                requested.stream().map(c -> new CommentOutcome(c, Delivery.UNKNOWN, null)).toList(), detail);
    }
}
