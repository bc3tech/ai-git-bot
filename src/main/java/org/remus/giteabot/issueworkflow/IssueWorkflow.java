package org.remus.giteabot.issueworkflow;

import org.remus.giteabot.prworkflow.WorkflowDescriptor;

/**
 * Strategy contract for one pluggable workflow that runs when a bot is
 * assigned to an issue ({@link #onIssueAssigned}) and when it receives
 * follow-up comments on that issue ({@link #onIssueComment}). This is the
 * issue-side counterpart of {@code org.remus.giteabot.prworkflow.PrWorkflow}.
 *
 * <p>Implementations are registered automatically through Spring DI and
 * discovered by {@link IssueWorkflowRegistry}. The two built-in
 * compatibility workflows are {@code issue-coding} (delegates to
 * {@code IssueImplementationService}) and {@code issue-writer} (delegates to
 * {@code WriterAgentService}). Which workflow(s) run for a bot is resolved
 * from the bot's issue-assigned {@code WorkflowConfiguration} (kind
 * {@code ISSUE}) by {@link IssueWorkflowOrchestrator} — never from a
 * hardcoded bot category.</p>
 *
 * <p>Comment handling is part of the contract from day one: both events
 * belong to the same flow lifecycle, so implementations must consciously
 * decide their follow-up behavior instead of inheriting a silent no-op.</p>
 */
public interface IssueWorkflow extends WorkflowDescriptor {

    /**
     * Executes the workflow for an issue-assigned event. Runtime exceptions
     * are caught by {@link IssueWorkflowOrchestrator}, recorded as a bot
     * error and published as an {@code issueassignment.failed} outgoing
     * event.
     *
     * @param context immutable run context, never {@code null}
     */
    void onIssueAssigned(IssueWorkflowContext context);

    /**
     * Handles a follow-up comment on an issue the bot is involved in.
     * Runtime exceptions are caught by {@link IssueWorkflowOrchestrator} and
     * recorded as a bot error (no outgoing events, matching the legacy
     * behavior).
     *
     * @param context immutable run context, never {@code null}
     */
    void onIssueComment(IssueWorkflowContext context);
}
