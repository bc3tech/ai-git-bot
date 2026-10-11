package org.remus.giteabot.prworkflow.agentreview;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.repository.RepositoryApiClient;

/** Publishes one bounded, plain PR comment for an agentic-review failure. */
@Slf4j
public final class ReviewFailureReporter {

    public record Delivery(ReviewDiagnostics.NoticeStatus status, String reason) {}

    public Delivery report(RepositoryApiClient client, String owner, String repo, Long pullRequestNumber,
                           ReviewDiagnostics.Snapshot failure, boolean clarification) {
        if (owner == null || owner.isBlank() || repo == null || repo.isBlank()
                || pullRequestNumber == null || pullRequestNumber <= 0) {
            return unavailable("pull-request-identity-unavailable");
        }
        if (client == null) {
            return unavailable("repository-client-unavailable");
        }

        String body = notice(failure, clarification);
        try {
            client.postPullRequestComment(owner, repo, pullRequestNumber, body);
            log.info("Agentic review failure notice delivered: {}", failure.details());
            return new Delivery(ReviewDiagnostics.NoticeStatus.DELIVERED, null);
        } catch (RuntimeException e) {
            log.warn("Agentic review failure notice delivery failed: {}", failure.details());
            return new Delivery(ReviewDiagnostics.NoticeStatus.FAILED, "delivery-failed");
        }
    }

    private Delivery unavailable(String reason) {
        return new Delivery(ReviewDiagnostics.NoticeStatus.UNAVAILABLE, reason);
    }

    private String notice(ReviewDiagnostics.Snapshot failure, boolean clarification) {
        String noun = clarification ? "clarification response" : "review";
        String result = switch (failure.category()) {
            case CONTEXT_ROUND_LIMIT -> "No completed " + noun + " was produced. Reason: context-round-limit. "
                    + "The context-exploration "
                    + "round limit was reached (" + failure.contextRounds() + "/"
                    + available(failure.contextLimit()) + " rounds; " + failure.totalTurns()
                    + " total turns). Check the run diagnostics for repeated context requests; "
                    + "raising a limit is not guaranteed to resolve a stalled run.";
            case LOOP_ROUND_LIMIT -> "No completed " + noun + " was produced. Reason: loop-round-limit. "
                    + "The overall loop-round "
                    + "limit was reached (" + failure.totalTurns() + "/"
                    + available(failure.loopLimit()) + " turns). Check the run diagnostics before "
                    + "changing limits.";
            case OUTPUT_TOKEN_LIMIT -> "No completed " + noun + " was produced. Reason: output-token-limit. "
                    + "The provider "
                    + "reached the output-token limit (limit " + available(failure.outputTokenLimit())
                    + "; reported usage " + available(failure.outputTokens()) + "). Check the run "
                    + "diagnostics and provider output settings.";
            case PUBLICATION_FAILURE -> "The completed " + noun + " could not be published. "
                    + "Reason: publication-failure. Check "
                    + "repository permissions and the bot run diagnostics.";
            case PROVIDER_REQUEST_FAILURE -> "No completed " + noun + " was produced. "
                    + "Reason: provider-request-failure. The "
                    + "provider request failed. Check provider availability and the bot run diagnostics.";
            case SETUP_FAILURE -> "No completed " + noun + " was produced. Reason: setup-failure. "
                    + "Review setup failed. "
                    + "Check the bot run diagnostics.";
            case EMPTY_FEEDBACK -> "No completed " + noun + " was produced. Reason: empty-feedback. "
                    + "The final feedback "
                    + "was empty. Check the bot run diagnostics.";
            case INCOMPLETE_COMPLETION -> "No completed " + noun + " was produced. "
                    + "Reason: incomplete-completion. The provider turn was not complete. "
                    + "Check the bot run diagnostics.";
            case UNKNOWN_COMPLETION -> "No completed " + noun + " was produced. "
                    + "Reason: unknown-completion. The provider completion reason was unknown. "
                    + "Check the bot run diagnostics.";
            case UNKNOWN_EXECUTION_FAILURE -> "No completed " + noun + " was produced. "
                    + "Reason: unknown-execution-failure. The run "
                    + "failed for an unknown reason. Check the bot run diagnostics.";
        };
        return "⚠️ **AI Agent (" + (clarification ? "Clarification" : "Review")
                + ") failure**: " + result;
    }

    private static String available(Object value) {
        return value == null ? "unavailable" : value.toString();
    }
}
