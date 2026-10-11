package org.remus.giteabot.review;

/**
 * Supersession check invoked immediately before every remote write a review makes.
 * Implementations throw (typically {@code WorkflowCancelledException}) when the run
 * is no longer the latest review for its pull request.
 */
@FunctionalInterface
public interface ReviewFence {

    ReviewFence NONE = location -> { };

    void requireActive(String location);
}
