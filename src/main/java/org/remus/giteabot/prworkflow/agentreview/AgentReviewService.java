package org.remus.giteabot.prworkflow.agentreview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.agent.issueimpl.AiResponseParser;
import org.remus.giteabot.agent.loop.AgentBudget;
import org.remus.giteabot.agent.loop.AgentLoop;
import org.remus.giteabot.agent.loop.AgentRunContext;
import org.remus.giteabot.agent.loop.ContextBudgetExceededException;
import org.remus.giteabot.agent.loop.LoopOutcome;
import org.remus.giteabot.agent.loop.ToolingMode;
import org.remus.giteabot.agent.session.AgentSession;
import org.remus.giteabot.agent.session.AgentSessionService;
import org.remus.giteabot.agent.shared.BranchRefs;
import org.remus.giteabot.agent.shared.BranchSwitcher;
import org.remus.giteabot.agent.shared.SystemPromptAssembler;
import org.remus.giteabot.agent.tools.AgentToolRouter;
import org.remus.giteabot.agent.tools.ToolCatalog;
import org.remus.giteabot.agent.validation.ToolExecutionService;
import org.remus.giteabot.agent.validation.WorkspaceResult;
import org.remus.giteabot.agent.validation.WorkspaceService;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.audit.ActorType;
import org.remus.giteabot.audit.PrAuditEvent;
import org.remus.giteabot.audit.PrAuditEventService;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.eventhook.EventHookEventType;
import org.remus.giteabot.eventhook.EventHookPublisher;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.mcp.McpOrchestrationService;
import org.remus.giteabot.mcp.McpToolCatalog;
import org.remus.giteabot.prworkflow.WorkflowCancelledException;
import org.remus.giteabot.repository.PostReviewAction;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.model.ReviewSnapshot;
import org.remus.giteabot.review.ReviewDocument;
import org.remus.giteabot.review.ReviewFence;
import org.remus.giteabot.review.ReviewOutputInstructions;
import org.remus.giteabot.review.ReviewOutputParser;
import org.remus.giteabot.review.ReviewPublicationService;
import org.remus.giteabot.review.ReviewSkipService;
import org.remus.giteabot.review.grounding.DoubleCheckService.Budget;
import org.remus.giteabot.review.grounding.DoubleCheckService.Evidence;
import org.remus.giteabot.review.grounding.DoubleCheckSettings;
import org.remus.giteabot.review.grounding.FindingGroundingService;
import org.remus.giteabot.review.grounding.GroundingResult;
import org.remus.giteabot.review.grounding.PinnedSourceReader;
import org.remus.giteabot.review.grounding.PriorFindingsDigest;
import org.remus.giteabot.review.grounding.PriorFindingsService;
import org.remus.giteabot.review.grounding.ThreadActionExecutor;
import org.remus.giteabot.review.state.ReviewRevisionRecord;
import org.remus.giteabot.review.state.ReviewRunLeaseService.LeaseAcquisitionTimeoutException;
import org.remus.giteabot.review.state.ReviewRunLeaseService.LeaseLostException;
import org.remus.giteabot.review.state.ReviewRunLeaseService.LeaseToken;
import org.remus.giteabot.review.state.ReviewRunLeaseService;
import org.remus.giteabot.review.state.ReviewStateService;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Core business logic of the agentic PR-review workflow. Not a Spring
 * singleton — instances are created per-bot by {@link AgentReviewServiceFactory}
 * with the bot's own {@link AiClient} and {@link RepositoryApiClient}.
 *
 * <p>The flow mirrors {@link org.remus.giteabot.agent.IssueImplementationService}
 * but is strictly read-only: it clones a workspace, lets the LLM explore the
 * repository through {@link ToolCatalog.Role#REVIEW} (read-only) tools and selected MCP,
 * then posts a single review comment. When the operator enables the optional
 * formal review decision, the model classifies its findings by severity and the
 * application computes the formal action (approve / request changes) from
 * configured thresholds.</p>
 */
@Slf4j
public class AgentReviewService {

    /**
     * Fixed instruction appended to the system prompt when formal review decisions
     * are enabled. Defines the exact structured return format.
     */
    static final String DECISION_FORMAT_INSTRUCTION = """


            ## Formal Review Decision Output Format

            The last line of your response MUST be exactly one JSON object and nothing else:

            {"blocker": 0, "medium": 1, "low": 2}

            The values are integer counts of findings for each severity class.
            Do not wrap it in a code fence and do not write anything after it.

            Optionally include a "findings" array next to the counts:
            {"blocker": 1, "medium": 0, "low": 0, "findings": [{"severity": "blocker", "category": "security", "title": "...", "file": "src/Foo.java", "line": 42, "cwe": "CWE-89", "owasp": "A03:2021"}]}
            Omit unknown optional fields instead of guessing.""";

    /**
     * Matches a trailing classification JSON block, bare or fenced, regardless of
     * whether its values are valid — an invalid block is still detected and
     * stripped so it never leaks into the posted review. The body is one-level
     * brace-aware so the optional {@code findings} array (objects nested one
     * level deep) does not break the match; whether the block actually is a
     * severity classification is decided by {@link #containsSeverityKey(String)}.
     */
    private static final Pattern SEVERITY_JSON_PATTERN = Pattern.compile(
            "```json\\s*\\n?\\s*(\\{(?:[^{}]|\\{[^{}]*})*})\\s*\\n?\\s*```\\s*\\z",
            Pattern.DOTALL);

    private static final Pattern SEVERITY_BARE_PATTERN = Pattern.compile(
            "\\{(?:[^{}]|\\{[^{}]*})*}\\s*\\z",
            Pattern.MULTILINE);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * Operator-configured severity thresholds for deterministic formal review
     * decisions. A {@code null} value means that severity is ignored.
     */
    public record SeverityThresholds(Integer blocker, Integer medium, Integer low) {
    }

    /**
     * Severity classification returned by the model, with integer counts per class.
     */
    public record SeverityClassification(int blocker, int medium, int low) {
    }

    /** Distinguishes a posted review or clarification from a no-op and a failed attempt. */
    public enum ReviewResult {
        POSTED, NO_DIFF, FAILED, SKIPPED
    }

    public record DetailedResult(ReviewResult result, ReviewDiagnostics.Snapshot failure,
                                 ReviewDiagnostics.NoticeStatus noticeStatus, String noticeReason) {}

    private enum FailureStage {
        SETUP, LOOP, POSTPROCESS, PUBLICATION
    }

    private final AgentReviewContext context;
    private final Bot bot;
    private final EventHookPublisher eventHookPublisher;
    private final AgentSessionService sessionService;
    private final ToolCatalog toolCatalog;
    private final WorkspaceService workspaceService;
    private final AgentConfigProperties agentConfig;

    private final ReviewRunLeaseService leases;
    private final ReviewStateService state;
    private final PriorFindingsService priors;
    private final FindingGroundingService grounding;
    private final ThreadActionExecutor threadActions;
    private final ReviewSkipService skipService;
    private final PrAuditEventService audits;
    private final Duration leaseTtl;
    private final DoubleCheckSettings doubleCheckSettings;
    private final Budget doubleCheckBudget;

    private final RepositoryApiClient repositoryClient;
    private final AiClient aiClient;
    private final AgentToolRouter toolRouter;
    private final AiResponseParser responseParser = new AiResponseParser();
    private final BranchSwitcher branchSwitcher;
    private final SystemPromptAssembler systemPromptAssembler = new SystemPromptAssembler();

    public AgentReviewService(AgentReviewContext context,
                              AgentSessionService sessionService,
                              ToolExecutionService toolExecutionService,
                              ToolCatalog toolCatalog,
                              WorkspaceService workspaceService,
                              AgentConfigProperties agentConfig,
                              McpOrchestrationService mcpOrchestrationService,
                              Bot bot,
                              EventHookPublisher eventHookPublisher,
                              ReviewRunLeaseService leases, ReviewStateService state,
                              PriorFindingsService priors, FindingGroundingService grounding,
                              ThreadActionExecutor threadActions, ReviewSkipService skipService,
                              PrAuditEventService audits, Duration leaseTtl,
                              DoubleCheckSettings doubleCheckSettings, Budget doubleCheckBudget) {
        this.leases = leases;
        this.state = state;
        this.priors = priors;
        this.grounding = grounding;
        this.threadActions = threadActions;
        this.skipService = skipService;
        this.audits = audits;
        this.leaseTtl = leaseTtl;
        this.doubleCheckSettings = doubleCheckSettings;
        this.doubleCheckBudget = doubleCheckBudget;
        this.context = context;
        this.bot = bot;
        this.eventHookPublisher = eventHookPublisher;
        this.sessionService = sessionService;
        this.toolCatalog = toolCatalog;
        this.workspaceService = workspaceService;
        this.agentConfig = agentConfig;
        this.repositoryClient = context.repositoryClient();
        this.aiClient = context.aiClient();
        this.branchSwitcher = new BranchSwitcher(toolExecutionService);
        this.toolRouter = new AgentToolRouter(toolExecutionService, toolCatalog,
                mcpOrchestrationService, context.mcpConfiguration(),
                context.mcpToolCatalog(), this.repositoryClient, context.allowedBuiltinTools());
    }

    /**
     * Runs an agentic review for the PR described by {@code payload} and posts
     * the resulting review as a PR comment. When {@code enableFormalDecision}
     * is {@code true}, the system prompt is extended with the operator-
     * configured criteria and a fixed format instruction; the model's output
     * is parsed for a severity classification, and the formal review action
     * (APPROVE / REQUEST_CHANGES / NONE) is computed deterministically from
     * {@code severityThresholds}. The action is submitted together with the
     * review body via {@link RepositoryApiClient#postReview}.
     *
     * @param maxToolRounds         operator-tunable cap on the number of
     *                              explore/answer rounds (clamped to a sane range)
     * @param enableFormalDecision  when true, the model returns a severity
     *                              classification and the action is derived from
     *                              the configured thresholds
     * @param decisionPrompt        operator-provided criteria for the severity
     *                              classification
     * @param severityThresholds    optional per-severity thresholds used to
     *                              compute the final review action
     * @return whether a review was posted, no diff was available, or the review failed
     */
    public ReviewResult reviewPullRequest(WebhookPayload payload, int maxToolRounds,
                                         boolean enableFormalDecision, String decisionPrompt,
                                         SeverityThresholds severityThresholds,
                                         Long runId,
                                         Consumer<AgentRunContext.ToolCallRecord> toolCallConsumer) {
        return reviewPullRequestDetailed(payload, maxToolRounds, enableFormalDecision, decisionPrompt,
                severityThresholds, runId, toolCallConsumer).result();
    }

    public DetailedResult reviewPullRequestDetailed(WebhookPayload payload, int maxToolRounds,
                                                    boolean enableFormalDecision, String decisionPrompt,
                                                    SeverityThresholds severityThresholds, Long runId,
                                                    Consumer<AgentRunContext.ToolCallRecord> toolCallConsumer) {
        return reviewPullRequestDetailed(payload, maxToolRounds, enableFormalDecision, decisionPrompt,
                severityThresholds, runId, toolCallConsumer, ReviewFence.NONE, false);
    }

    /**
     * Same as {@link #reviewPullRequest(WebhookPayload, int, boolean, String, SeverityThresholds, Long, Consumer)},
     * checking {@code fence} before publishing. A cancelled run propagates its
     * {@link WorkflowCancelledException} instead of posting an error comment.
     */
    public ReviewResult reviewPullRequest(WebhookPayload payload, int maxToolRounds,
                                         boolean enableFormalDecision, String decisionPrompt,
                                         SeverityThresholds severityThresholds,
                                         Long runId,
                                         Consumer<AgentRunContext.ToolCallRecord> toolCallConsumer,
                                         ReviewFence fence) {
        return reviewPullRequest(payload, maxToolRounds, enableFormalDecision, decisionPrompt, severityThresholds,
                runId, toolCallConsumer, fence, false);
    }

    public ReviewResult reviewPullRequest(WebhookPayload payload, int maxToolRounds,
                                         boolean enableFormalDecision, String decisionPrompt,
                                         SeverityThresholds severityThresholds, Long runId,
                                         Consumer<AgentRunContext.ToolCallRecord> toolCallConsumer,
                                         ReviewFence fence, boolean explicitRequest) {
        return reviewPullRequestDetailed(payload, maxToolRounds, enableFormalDecision, decisionPrompt,
                severityThresholds, runId, toolCallConsumer, fence, explicitRequest).result();
    }

    public DetailedResult reviewPullRequestDetailed(WebhookPayload payload, int maxToolRounds,
                                                    boolean enableFormalDecision, String decisionPrompt,
                                                    SeverityThresholds severityThresholds, Long runId,
                                                    Consumer<AgentRunContext.ToolCallRecord> toolCallConsumer,
                                                    ReviewFence fence, boolean explicitRequest) {
        String owner = owner(payload);
        String repo = repo(payload);
        Long prNumber = pullRequestNumber(payload);
        String prTitle = payload != null && payload.getPullRequest() != null
                ? payload.getPullRequest().getTitle() : null;
        String prBody = payload != null && payload.getPullRequest() != null
                ? payload.getPullRequest().getBody() : null;
        ReviewDiagnostics.Collector diagnostics = newCollector(maxToolRounds, runId, owner, repo, prNumber);
        PrAuditEvent event = PrAuditEvent.builder().botId(bot == null ? null : bot.getId()).repoOwner(owner).repoName(repo)
                .prNumber(prNumber).runId(runId).actorType(ActorType.BOT.name()).actorId(context.botUsername())
                .stepName(AgentReviewWorkflow.KEY).build();
        LeaseToken lease = null;
        AgentReviewLeaseHeartbeat heartbeat = null;
        ReviewSnapshot snapshot = null;
        ReviewFence runFence = fence;
        Path workspaceDir = null;
        FailureStage stage = FailureStage.SETUP;
        boolean reviewDelivered = false;

        try {
            if (owner == null || repo == null || prNumber == null) {
                return reportFailure(diagnostics.snapshot(ReviewDiagnostics.Category.SETUP_FAILURE),
                        owner, repo, prNumber, false, runFence);
            }
            log.info("Starting agentic review for PR #{} '{}' in {}/{} (formalDecision={})",
                    prNumber, prTitle, owner, repo, enableFormalDecision);

            fence.requireActive("before acquiring agentic review lease");
            lease = leases.acquire(bot.getId(), owner, repo, prNumber);
            heartbeat = new AgentReviewLeaseHeartbeat(leases, lease, leaseTtl);
            LeaseToken held = lease;
            AgentReviewLeaseHeartbeat renewal = heartbeat;
            runFence = location -> {
                fence.requireActive(location);
                renewal.assertHealthy();
                leases.assertHeld(held);
            };
            runFence.requireActive("before reading agentic review snapshot");
            snapshot = ReviewPublicationService.snapshot(repositoryClient, owner, repo, prNumber);
            PriorFindingsDigest digest = priors.load(repositoryClient, lease, AgentReviewWorkflow.KEY, context.botUsername());
            if (skipService.shouldSkip(repositoryClient, lease, AgentReviewWorkflow.KEY, context.botUsername(),
                    snapshot, explicitRequest)) {
                runFence.requireActive("before posting unchanged revision reply");
                repositoryClient.postPullRequestComment(owner, repo, prNumber, "No changes since last review at `"
                        + snapshot.headSha().substring(0, Math.min(7, snapshot.headSha().length())) + "`.");
                clearAcknowledgement(payload, runFence);
                return result(ReviewResult.SKIPPED);
            }
            if (snapshot.diff() == null || snapshot.diff().isBlank()) return result(ReviewResult.NO_DIFF);
            DiffSummary diffSummary = DiffSummary.parse(snapshot.diff());
            PinnedSourceReader head = snapshot.headSha() == null ? null
                    : new PinnedSourceReader(repositoryClient, owner, repo, snapshot.headSha());
            PinnedSourceReader base = snapshot.pinned()
                    ? new PinnedSourceReader(repositoryClient, owner, repo, snapshot.effectiveBaseSha()) : null;
            if (head != null) {
                WorkspaceResult workspace = workspaceService.prepareWorkspaceAtRevision(
                        repositoryClient, owner, repo, snapshot.headSha(), prNumber);
                if (!workspace.success()) throw new IllegalStateException(workspace.error());
                workspaceDir = workspace.workspacePath();
            }
            String systemPrompt = resolveSystemPrompt(enableFormalDecision, decisionPrompt);
            String userMessage = buildKickoffMessage(prTitle, prBody, diffSummary, enableFormalDecision)
                    + "\n\n" + digest.promptBlock();
            if (head == null) userMessage += "\nNo pinned source revision is available. Review only this diff; do not infer full-file contents.\n\n"
                    + snapshot.diff();
            AgentSession session = new AgentSession(owner, repo, prNumber, prTitle);
            runFence.requireActive("before running agentic review loop");
            stage = FailureStage.LOOP;
            List<String> coverageNotes = new ArrayList<>();
            LoopOutcome outcome = runReviewLoop(session, owner, repo, prNumber, workspaceDir, snapshot.headSha(),
                    systemPrompt, userMessage, maxToolRounds, diffSummary, runId, toolCallConsumer, head, runFence,
                    diagnostics, true, coverageNotes);
            if (!outcome.success()) {
                ReviewDiagnostics.Snapshot failure = outcome.payload() instanceof ReviewDiagnostics.Snapshot detail
                        ? detail : diagnostics.snapshot(ReviewDiagnostics.Category.UNKNOWN_EXECUTION_FAILURE);
                return reportFailure(failure, owner, repo, prNumber, false, runFence);
            }
            String review = outcome.payload() instanceof String text ? text : null;
            if (review == null || review.isBlank()) {
                return reportFailure(diagnostics.snapshot(ReviewDiagnostics.Category.EMPTY_FEEDBACK),
                        owner, repo, prNumber, false, runFence);
            }
            stage = FailureStage.POSTPROCESS;
            String reviewText = enableFormalDecision ? parseFormalReviewResult(review, null).reviewText() : review;
            ReviewOutputParser.Result output = ReviewOutputParser.parse(reviewText);
            ReviewDocument document = output.document();
            GroundingResult grounded = grounding.ground(document, new Evidence(head, base, snapshot.diff()), digest,
                    state.findingsForDedup(bot.getId(), owner, repo, prNumber, AgentReviewWorkflow.KEY),
                    doubleCheckSettings, aiClient, doubleCheckBudget, event, runFence);
            if (!coverageNotes.isEmpty()) {
                List<String> notes = new ArrayList<>(grounded.summaryNotes());
                notes.addAll(coverageNotes);
                grounded = new GroundingResult(grounded.publish(), notes, grounded.threadActions(),
                        grounded.dispositions(), grounded.evidence(), grounded.supersedes(), true);
            }
            ParseResult decision = groundedDecision(document.summary(), grounded, enableFormalDecision, severityThresholds);
            PostReviewAction publicationAction = decision.action() == PostReviewAction.APPROVE
                    ? PostReviewAction.NONE : decision.action();
            stage = FailureStage.PUBLICATION;
            ReviewPublicationService.Outcome published = ReviewPublicationService.publish(repositoryClient, snapshot,
                    document.summary(), grounded, publicationAction, this::formatReview, runFence,
                    state, leases, lease, AgentReviewWorkflow.KEY, audits, event);
            if (!published.delivered()) {
                // Publication owns stale/partial delivery recovery; never replay its writes.
                return failureWithoutNotice(diagnostics.snapshot(ReviewDiagnostics.Category.PUBLICATION_FAILURE),
                        "publication-not-delivered");
            }
            reviewDelivered = true;
            boolean threadFailures = !threadActions.execute(repositoryClient, lease, grounded.threadActions(),
                    context.botUsername(), event, snapshot.headSha(), snapshot.effectiveBaseSha(), runFence).isEmpty();
            boolean degraded = grounded.degraded() || threadFailures || !digest.commentsComplete()
                    || !published.approvalSafe();
            runFence.requireActive("before recording reviewed agentic revision");
            ReviewRevisionRecord revision = new ReviewRevisionRecord();
            revision.setBotId(bot.getId());
            revision.setRepoOwner(owner);
            revision.setRepoName(repo);
            revision.setPrNumber(prNumber);
            revision.setWorkflowKey(AgentReviewWorkflow.KEY);
            revision.setHeadSha(snapshot.headSha());
            revision.setEffectiveBaseSha(snapshot.effectiveBaseSha());
            revision.setReviewedAt(Instant.now());
            revision.setDegraded(degraded);
            if (digest.commentCursor() != null) {
                revision.setLastSeenCommentAt(digest.commentCursor().timestamp());
                revision.setLastSeenCommentId(digest.commentCursor().id());
            }
            state.upsertRevision(revision, lease);
            if (decision.action() == PostReviewAction.APPROVE && !degraded) {
                runFence.requireActive("before approving completed agentic review");
                repositoryClient.postReviewAction(owner, repo, prNumber, PostReviewAction.APPROVE);
            }
            publishFindingEvents(eventHookPublisher, bot, decision, owner, repo, prNumber);
            return result(ReviewResult.POSTED);
        } catch (WorkflowCancelledException e) {
            throw e;
        } catch (LeaseLostException e) {
            audits.recordGroundingWarning(event, "AGENT_REVIEW", "lease lost",
                    snapshot == null ? null : snapshot.headSha(), snapshot == null ? null : snapshot.effectiveBaseSha());
            return failureWithoutNotice(diagnostics.snapshot(ReviewDiagnostics.Category.UNKNOWN_EXECUTION_FAILURE),
                    "lease-lost");
        } catch (Exception e) {
            ReviewDiagnostics.Category category = e instanceof ContextBudgetExceededException
                    ? ReviewDiagnostics.Category.MANDATORY_CONTEXT_LIMIT
                    : e instanceof StalledReviewContextException
                    ? ReviewDiagnostics.Category.STALLED_CONTEXT : failureCategory(stage, diagnostics);
            ReviewDiagnostics.Snapshot failure = diagnostics.snapshot(category);
            if (reviewDelivered) {
                return failureWithoutNotice(failure, "review-already-delivered");
            }
            try {
                DetailedResult failureResult = reportFailure(failure, owner, repo, prNumber, false, runFence);
                if (e instanceof LeaseAcquisitionTimeoutException) clearAcknowledgement(payload, fence);
                return failureResult;
            } catch (LeaseLostException lost) {
                audits.recordGroundingWarning(event, "AGENT_REVIEW", "lease lost",
                        snapshot == null ? null : snapshot.headSha(), snapshot == null ? null : snapshot.effectiveBaseSha());
                return failureWithoutNotice(failure, "lease-lost");
            }
        } finally {
            try {
                if (workspaceDir != null) workspaceService.cleanupWorkspace(workspaceDir);
            } finally {
                try {
                    if (heartbeat != null) heartbeat.close();
                } finally {
                    if (lease != null) {
                        try { leases.release(lease); }
                        catch (RuntimeException e) { log.warn("Could not release agentic PR review lease; relying on expiry: {}", e.getMessage()); }
                    }
                }
            }
        }
    }

    private void clearAcknowledgement(WebhookPayload payload, ReviewFence fence) {
        String owner = payload.getRepository().getOwner().getLogin();
        String repo = payload.getRepository().getName();
        fence.requireActive("before clearing agentic review acknowledgement");
        try {
            repositoryClient.removePullRequestReaction(owner, repo, payload.getPullRequest().getNumber(), "eyes");
        } catch (LeaseLostException | WorkflowCancelledException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("Could not clear agentic review acknowledgement: {}", e.getMessage());
        }
        if (payload.getComment() != null && payload.getComment().getId() != null) {
            fence.requireActive("before clearing agentic review comment acknowledgement");
            try {
                repositoryClient.removeReaction(owner, repo, payload.getComment().getId(), "eyes");
            } catch (LeaseLostException | WorkflowCancelledException e) {
                throw e;
            } catch (RuntimeException e) {
                log.warn("Could not clear agentic review comment acknowledgement: {}", e.getMessage());
            }
        }
    }

    static ParseResult groundedDecision(String summary, GroundingResult grounded, boolean enabled,
                                        SeverityThresholds thresholds) {
        int blocker = 0;
        int medium = 0;
        int low = 0;
        List<Map<String, Object>> findings = new ArrayList<>();
        for (ReviewDocument.Finding finding : grounded.publish()) {
            switch (DoubleCheckSettings.Severity.parse(finding.severity())) {
                case BLOCKER, HIGH -> blocker++;
                case MEDIUM -> medium++;
                case LOW -> low++;
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("severity", finding.severity() == null ? "LOW" : finding.severity());
            data.put("title", finding.body());
            if (finding.category() != null) data.put("category", finding.category());
            if (finding.path() != null) data.put("file", finding.path());
            if (finding.line() != null) data.put("line", finding.line());
            findings.add(data);
        }
        SeverityClassification classification = new SeverityClassification(blocker, medium, low);
        PostReviewAction action = enabled ? evaluateAction(classification, thresholds) : PostReviewAction.NONE;
        if (grounded.degraded() && action == PostReviewAction.APPROVE) action = PostReviewAction.NONE;
        return new ParseResult(summary, action, classification, findings);
    }

    /**
     * Answers a clarification question about a previously-reviewed PR by running
     * a conversational agent loop. Formal review decisions are not applicable here.
     */
    public ReviewResult answerClarification(WebhookPayload payload, String userQuestion, int maxToolRounds) {
        return answerClarificationDetailed(payload, userQuestion, maxToolRounds, null).result();
    }

    public DetailedResult answerClarificationDetailed(WebhookPayload payload, String userQuestion,
                                                      int maxToolRounds, Long runId) {
        String owner = owner(payload);
        String repo = repo(payload);
        Long prNumber = pullRequestNumber(payload);
        String prTitle = payload != null && payload.getPullRequest() != null
                ? payload.getPullRequest().getTitle() : null;
        String prBody = payload != null && payload.getPullRequest() != null
                ? payload.getPullRequest().getBody() : null;
        ReviewDiagnostics.Collector diagnostics = newCollector(maxToolRounds, runId, owner, repo, prNumber);
        Path workspaceDir = null;
        FailureStage stage = FailureStage.SETUP;

        try {
            if (owner == null || repo == null || prNumber == null) {
                return reportFailure(diagnostics.snapshot(ReviewDiagnostics.Category.SETUP_FAILURE),
                        owner, repo, prNumber, true);
            }
            log.info("Answering clarification for PR #{} '{}' in {}/{}", prNumber, prTitle, owner, repo);
            String diff = repositoryClient.getPullRequestDiff(owner, repo, prNumber);
            if (diff == null || diff.isBlank()) {
                log.warn("No diff found for PR #{} in {}/{} — cannot answer clarification", prNumber, owner, repo);
                return new DetailedResult(ReviewResult.NO_DIFF, null,
                        ReviewDiagnostics.NoticeStatus.NOT_ATTEMPTED, null);
            }

            DiffSummary diffSummary = DiffSummary.parse(diff);
            log.info("Parsed diff summary for clarification on PR #{}: {}", prNumber, diffSummary.statLine());
            String headBranch = resolveHeadBranch(payload, owner, repo, prNumber);
            if (headBranch == null) {
                return reportFailure(diagnostics.snapshot(ReviewDiagnostics.Category.SETUP_FAILURE),
                        owner, repo, prNumber, true);
            }

            WorkspaceResult wsResult = workspaceService.prepareWorkspace(
                    repositoryClient, owner, repo, headBranch, prNumber);
            if (!wsResult.success()) {
                log.warn("Failed to prepare workspace for clarification on PR #{}", prNumber);
                return reportFailure(diagnostics.snapshot(ReviewDiagnostics.Category.SETUP_FAILURE),
                        owner, repo, prNumber, true);
            }
            workspaceDir = wsResult.workspacePath();
            String systemPrompt = resolveSystemPrompt(false, null, false);
            String userMessage = buildClarificationMessage(prTitle, prBody, diffSummary, userQuestion);
            AgentSession session = new AgentSession(owner, repo, prNumber, prTitle);

            stage = FailureStage.LOOP;
            LoopOutcome outcome = runReviewLoop(session, owner, repo, prNumber,
                    workspaceDir, headBranch, systemPrompt, userMessage, maxToolRounds,
                    diffSummary, runId, null, diagnostics);
            if (!outcome.success()) {
                ReviewDiagnostics.Snapshot failure = outcome.payload() instanceof ReviewDiagnostics.Snapshot snapshot
                        ? snapshot : diagnostics.snapshot(ReviewDiagnostics.Category.UNKNOWN_EXECUTION_FAILURE);
                return reportFailure(failure, owner, repo, prNumber, true);
            }

            String answer = outcome.payload() instanceof String s ? s : null;
            if (answer == null || answer.isBlank()) {
                return reportFailure(diagnostics.snapshot(ReviewDiagnostics.Category.EMPTY_FEEDBACK),
                        owner, repo, prNumber, true);
            }

            stage = FailureStage.PUBLICATION;
            repositoryClient.postPullRequestComment(owner, repo, prNumber, formatClarification(answer));
            log.info("Clarification answered for PR #{} in {}/{}", prNumber, owner, repo);
            return new DetailedResult(ReviewResult.POSTED, null,
                    ReviewDiagnostics.NoticeStatus.NOT_ATTEMPTED, null);
        } catch (WorkflowCancelledException e) {
            throw e;
        } catch (Exception e) {
            ReviewDiagnostics.Category category = failureCategory(stage, diagnostics);
            return reportFailure(diagnostics.snapshot(category), owner, repo, prNumber, true);
        } finally {
            if (workspaceDir != null) {
                workspaceService.cleanupWorkspace(workspaceDir);
            }
        }
    }

    /**
     * Parses a severity classification from the model output, strips the trailing
     * classification block so the review comment is clean, and computes the formal
     * {@link PostReviewAction} from the configured thresholds. A detected block is
     * stripped regardless of validity; an unparseable value yields {@code NONE}
     * (fail-open) but still-cleaned text.
     */
    static ParseResult parseFormalReviewResult(String review, SeverityThresholds thresholds) {
        if (review == null || review.isBlank()) {
            return ParseResult.noDecision(review);
        }

        if (ReviewOutputParser.parse(review).document().structured()) {
            String json = review.strip();
            if (json.startsWith("```")) {
                int start = json.indexOf('\n');
                int end = json.lastIndexOf("```");
                if (start >= 0 && end > start && json.substring(end + 3).isBlank()) {
                    json = json.substring(start + 1, end).strip();
                }
            }
            try {
                JsonNode root = OBJECT_MAPPER.reader()
                        .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .readTree(json);
                JsonNode classification = root.get("classification");
                if (classification != null) {
                    ClassificationBlock block = parseClassificationBlock(classification.toString());
                    return new ParseResult(review,
                            evaluateAction(block == null ? null : block.classification(), thresholds),
                            block == null ? null : block.classification(), parseFindings(root.get("findings")));
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                // Older responses may append a separate classification object.
            }
        }

        // Canonical form: a bare JSON object on the last line.
        Matcher bare = SEVERITY_BARE_PATTERN.matcher(review);
        if (bare.find() && containsSeverityKey(bare.group())) {
            String cleaned = review.substring(0, bare.start()).stripTrailing();
            return toParseResult(cleaned, parseClassificationBlock(bare.group()), thresholds);
        }

        // Tolerant fallback: a fenced ```json block at the end.
        Matcher fenced = SEVERITY_JSON_PATTERN.matcher(review);
        if (fenced.find() && containsSeverityKey(fenced.group(1))) {
            String cleaned = review.substring(0, fenced.start()).stripTrailing();
            return toParseResult(cleaned, parseClassificationBlock(fenced.group(1)), thresholds);
        }

        return ParseResult.noDecision(review);
    }

    /** A trailing JSON block only counts as a classification when it has a severity count key. */
    private static boolean containsSeverityKey(String json) {
        return SEVERITY_KEY_PATTERN.matcher(json).find();
    }

    private static final Pattern SEVERITY_KEY_PATTERN = Pattern.compile("\"(blocker|medium|low)\"\\s*:");

    private static ParseResult toParseResult(String cleaned, ClassificationBlock block,
                                             SeverityThresholds thresholds) {
        if (block == null) {
            // Detected but unparseable block: fail-open (NONE) with cleaned text.
            return new ParseResult(cleaned, evaluateAction(null, thresholds), null, List.of());
        }
        return new ParseResult(cleaned, evaluateAction(block.classification(), thresholds),
                block.classification(), block.findings());
    }

    /** Severity counts plus the optional structured findings array. */
    private record ClassificationBlock(SeverityClassification classification,
                                       List<Map<String, Object>> findings) {
    }

    private static ClassificationBlock parseClassificationBlock(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(json);
            SeverityClassification classification = new SeverityClassification(
                    intField(root, "blocker"),
                    intField(root, "medium"),
                    intField(root, "low"));
            return new ClassificationBlock(classification, parseFindings(root.get("findings")));
        } catch (Exception e) {
            log.warn("Failed to parse severity classification JSON; ignoring model classification");
            return null;
        }
    }

    /** Optional keys copied from a structured finding into the webhook payload. */
    private static final List<String> FINDING_STRING_KEYS =
            List.of("severity", "category", "title", "file", "cwe", "owasp");

    /**
     * Lenient extraction of the optional {@code findings} array: a missing or
     * non-array node yields an empty list; non-object elements are skipped; only
     * known keys are copied (strings textually, {@code line} as int). Unknown
     * fields and wrong-typed values are dropped rather than guessed.
     */
    private static List<Map<String, Object>> parseFindings(JsonNode findingsNode) {
        if (findingsNode == null || !findingsNode.isArray()) {
            return List.of();
        }
        List<Map<String, Object>> findings = new ArrayList<>();
        for (JsonNode element : findingsNode) {
            if (!element.isObject()) {
                continue;
            }
            Map<String, Object> finding = new LinkedHashMap<>();
            for (String key : FINDING_STRING_KEYS) {
                JsonNode value = element.get(key);
                if (value != null && value.isTextual()) {
                    finding.put(key, value.asText());
                }
            }
            JsonNode line = element.get("line");
            if (line != null && line.isInt()) {
                finding.put("line", line.asInt());
            }
            findings.add(finding);
        }
        return List.copyOf(findings);
    }

    /**
     * Emits one {@code agent.review.finding.detected} event per structured finding,
     * or a single aggregate event carrying the severity counts when the model only
     * returned counts. Package-private static seam for unit tests; the publisher
     * never throws, so emission can never affect the review outcome.
     */
    static void publishFindingEvents(EventHookPublisher publisher, Bot bot, ParseResult parsed,
                                     String owner, String repo, Long prNumber) {
        if (publisher == null || bot == null || parsed == null) {
            return;
        }
        if (!parsed.findings().isEmpty()) {
            for (Map<String, Object> finding : parsed.findings()) {
                publisher.publish(EventHookEventType.AGENT_REVIEW_FINDING_DETECTED, bot, owner, repo,
                        prNumber, null, Map.of("finding", finding));
            }
            return;
        }
        SeverityClassification counts = parsed.classification();
        if (counts != null && (counts.blocker() > 0 || counts.medium() > 0 || counts.low() > 0)) {
            publisher.publish(EventHookEventType.AGENT_REVIEW_FINDING_DETECTED, bot, owner, repo,
                    prNumber, null, Map.of("findingCounts", Map.of(
                            "blocker", counts.blocker(),
                            "medium", counts.medium(),
                            "low", counts.low())));
        }
    }

    private static int intField(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isMissingNode()) {
            return 0;
        }
        if (!node.canConvertToInt()) {
            throw new IllegalArgumentException("Field '" + field + "' is not an integer");
        }
        return node.asInt();
    }

    private static PostReviewAction evaluateAction(SeverityClassification classification,
                                                   SeverityThresholds thresholds) {
        if (classification == null || thresholds == null) {
            return PostReviewAction.NONE;
        }
        boolean anyConfigured = thresholds.blocker() != null
                || thresholds.medium() != null
                || thresholds.low() != null;
        if (!anyConfigured) {
            return PostReviewAction.NONE;
        }
        if (thresholds.blocker() != null && classification.blocker() > thresholds.blocker()) {
            return PostReviewAction.REQUEST_CHANGES;
        }
        if (thresholds.medium() != null && classification.medium() > thresholds.medium()) {
            return PostReviewAction.REQUEST_CHANGES;
        }
        if (thresholds.low() != null && classification.low() > thresholds.low()) {
            return PostReviewAction.REQUEST_CHANGES;
        }
        return PostReviewAction.APPROVE;
    }

    /**
     * Parsed result of extracting a formal review decision from model output.
     *
     * @param reviewText     the review text with the classification JSON stripped
     * @param action         the computed formal review action, or {@code null} when
     *                       no formal decision was requested
     * @param classification the parsed severity counts, or {@code null} when no
     *                       (valid) classification block was present
     * @param findings       the optional structured findings array (never null)
     */
    public record ParseResult(String reviewText, PostReviewAction action,
                              SeverityClassification classification,
                              List<Map<String, Object>> findings) {
        static ParseResult noDecision(String reviewText) {
            return new ParseResult(reviewText, null, null, List.of());
        }
    }

    private String buildClarificationMessage(String prTitle, String prBody, DiffSummary diffSummary, String userQuestion) {
        return """
                The user has a follow-up question about the pull request you reviewed earlier.

                PR Title: %s
                PR Description:
                %s

                The question is:

                %s

                The repository is checked out in your read-only workspace. Use your available \
                read-only tools to inspect the relevant code and answer the question \
                concisely. You cannot modify the repository.

                Changed files (%s):

                %s

                To see the diff hunks for a specific file, call the `pr-diff` tool with the \
                file path. To read the full current content of a file, use `cat`. To understand \
                a file's structure before reading it, use `ctags-signatures`.

                When you have gathered enough context, reply with your answer as plain \
                Markdown (no tool calls). Focus on answering the specific question asked.
                """.formatted(
                        prTitle == null ? "(none)" : prTitle,
                        prBody == null || prBody.isBlank() ? "(none)" : prBody,
                        userQuestion,
                        diffSummary.statLine(),
                        diffSummary.fileTable());
    }

    private String formatClarification(String answer) {
        return "## 🤖 Follow-up\n\n" + answer
                + "\n\n---\n*Read-only agentic review follow-up by AI Git Bot*";
    }

    private ReviewDiagnostics.Category failureCategory(FailureStage stage, ReviewDiagnostics.Collector diagnostics) {
        return switch (stage) {
            case SETUP -> ReviewDiagnostics.Category.SETUP_FAILURE;
            case PUBLICATION -> ReviewDiagnostics.Category.PUBLICATION_FAILURE;
            case LOOP, POSTPROCESS ->
                    diagnostics.snapshot(ReviewDiagnostics.Category.UNKNOWN_EXECUTION_FAILURE).providerCallFailed()
                            ? ReviewDiagnostics.Category.PROVIDER_REQUEST_FAILURE
                            : ReviewDiagnostics.Category.UNKNOWN_EXECUTION_FAILURE;
        };
    }

    private DetailedResult result(ReviewResult result) {
        return new DetailedResult(result, null, ReviewDiagnostics.NoticeStatus.NOT_ATTEMPTED, null);
    }

    private DetailedResult failureWithoutNotice(ReviewDiagnostics.Snapshot failure, String reason) {
        log.error("Agentic review failure: {}; noticeStatus={}; noticeReason={}",
                failure.details(), ReviewDiagnostics.NoticeStatus.NOT_ATTEMPTED, reason);
        return new DetailedResult(ReviewResult.FAILED, failure,
                ReviewDiagnostics.NoticeStatus.NOT_ATTEMPTED, reason);
    }

    private DetailedResult reportFailure(ReviewDiagnostics.Snapshot failure, String owner, String repo,
                                         Long prNumber, boolean clarification, ReviewFence fence) {
        fence.requireActive("before posting agentic review failure");
        DetailedResult result = reportFailure(failure, owner, repo, prNumber, clarification);
        fence.requireActive("after posting agentic review failure");
        return result;
    }

    private DetailedResult reportFailure(ReviewDiagnostics.Snapshot failure, String owner, String repo,
                                         Long prNumber, boolean clarification) {
        ReviewFailureReporter.Delivery delivery = new ReviewFailureReporter().report(
                repositoryClient, owner, repo, prNumber, failure, clarification);
        log.error("Agentic review failure: {}; noticeStatus={}; noticeReason={}",
                failure.details(), delivery.status(),
                delivery.reason() == null ? "unavailable" : delivery.reason());
        return new DetailedResult(ReviewResult.FAILED, failure, delivery.status(), delivery.reason());
    }

    private ReviewDiagnostics.Collector newCollector(int maxToolRounds, Long runId,
                                                     String owner, String repo, Long prNumber) {
        AgentConfigProperties.BudgetConfig budgetCfg = agentConfig.getBudget();
        int rounds = clamp(maxToolRounds, 1, 30);
        int hardCap = Math.max(budgetCfg.getMaxRounds(), rounds + 2);
        var integration = bot == null ? null : bot.getAiIntegration();
        return new ReviewDiagnostics.Collector(
                Math.max(1, budgetCfg.getMaxContextRounds()), hardCap, budgetCfg.getMaxTokensPerCall(),
                runId, owner, repo, prNumber,
                integration == null ? null : integration.getProviderType(),
                integration == null ? null : integration.getId());
    }

    public static String owner(WebhookPayload payload) {
        return payload == null || payload.getRepository() == null || payload.getRepository().getOwner() == null
                ? null : payload.getRepository().getOwner().getLogin();
    }

    public static String repo(WebhookPayload payload) {
        return payload == null || payload.getRepository() == null ? null : payload.getRepository().getName();
    }

    public static Long pullRequestNumber(WebhookPayload payload) {
        return payload == null || payload.getPullRequest() == null
                ? null : payload.getPullRequest().getNumber();
    }

    private LoopOutcome runReviewLoop(AgentSession session, String owner, String repo, Long prNumber,
                                      Path workspaceDir, String headBranch,
                                      String systemPrompt, String userMessage, int maxToolRounds,
                                      DiffSummary diffSummary, Long runId,
                                      Consumer<AgentRunContext.ToolCallRecord> toolCallConsumer,
                                      ReviewDiagnostics.Collector diagnostics) {
        return runReviewLoop(session, owner, repo, prNumber, workspaceDir, headBranch, systemPrompt, userMessage,
                maxToolRounds, diffSummary, runId, toolCallConsumer, null, ReviewFence.NONE, diagnostics,
                false, new ArrayList<>());
    }

    private LoopOutcome runReviewLoop(AgentSession session, String owner, String repo, Long prNumber,
                                      Path workspaceDir, String ref, String systemPrompt, String userMessage,
                                      int maxToolRounds, DiffSummary diffSummary, Long runId,
                                      Consumer<AgentRunContext.ToolCallRecord> toolCallConsumer,
                                      PinnedSourceReader source, ReviewFence fence,
                                      ReviewDiagnostics.Collector diagnostics, boolean fullReview,
                                      List<String> coverageNotes) {
        Set<String> tools = workspaceDir == null
                ? Set.of("pr-diff").stream().filter(context.allowedBuiltinTools()::contains)
                    .collect(java.util.stream.Collectors.toSet()) : context.allowedBuiltinTools();
        ReviewAgentStrategy strategy = new ReviewAgentStrategy(
                systemPrompt, toolRouter, toolCatalog,
                workspaceDir == null ? McpToolCatalog.empty() : context.mcpToolCatalog(), tools,
                responseParser, branchSwitcher,
                (o, r, requestedRef, paths) -> {
                    fence.requireActive("before fetching agentic review files");
                    if (source != null) return fetchPinnedFiles(source, paths);
                    return workspaceDir == null ? "No pinned source available; use the diff only." : fetchFiles(o, r, requestedRef, paths);
                }, agentConfig.getBudget().getMaxContextRounds(), fence, diagnostics);
        if (fullReview) {
            strategy.enableLegacyRecovery(agentConfig.getMaxFileContentChars())
                    .withFileEvidenceFetcher((o, r, requestedRef, path) -> {
                        fence.requireActive("before fetching pinned review evidence");
                        if (source == null || !source.revisionSha().equals(requestedRef)) {
                            return ReviewAgentStrategy.FileEvidence.failed();
                        }
                        try {
                            String content = source.read(path);
                            fence.requireActive("after fetching pinned review evidence");
                            return ReviewAgentStrategy.FileEvidence.complete(content);
                        } catch (PinnedSourceReader.RevisionUnavailableException e) {
                            fence.requireActive("after unavailable pinned review evidence");
                            return ReviewAgentStrategy.FileEvidence.failed();
                        }
                    });
        }

        AgentConfigProperties.BudgetConfig budgetCfg = agentConfig.getBudget();
        int rounds = clamp(maxToolRounds, 1, 30);
        int hardCap = Math.max(budgetCfg.getMaxRounds(), rounds + 2);
        AgentBudget budget = new AgentBudget(hardCap, budgetCfg.getMaxContextRounds(),
                budgetCfg.getMaxValidationRetries(), budgetCfg.getMaxTokensPerCall(),
                budgetCfg.getMaxToolResultChars(), budgetCfg.getMaxHistoryChars(),
                context.contextWindowTokens(), budgetCfg.getProactiveCompactionThreshold());

        AgentLoop loop = new AgentLoop(aiClient, sessionService, budget);
        AgentRunContext ctx = new AgentRunContext(session, owner, repo, prNumber, workspaceDir, ref);
        ctx.setDiffSummary(diffSummary);
        ctx.setAuditToolCallConsumer(toolCallConsumer);
        ctx.setModeObserver(diagnostics::observeMode);
        ctx.setRoundObserver(diagnostics::observeTurn);
        ctx.setProviderCallFailureObserver(diagnostics::providerCallFailed);
        LoopOutcome outcome = loop.run(ctx, userMessage, strategy);
        coverageNotes.addAll(strategy.coverageNotes());
        return outcome;
    }

    private String resolveSystemPrompt(boolean enableFormalDecision, String decisionPrompt) {
        return resolveSystemPrompt(enableFormalDecision, decisionPrompt, true);
    }

    private String resolveSystemPrompt(boolean enableFormalDecision, String decisionPrompt, boolean fullReview) {
        ToolingMode mode = (aiClient != null && aiClient.supportsNativeTools())
                ? ToolingMode.NATIVE : ToolingMode.LEGACY;
        String base = systemPromptAssembler.assemble(context.reviewAgentSystemPrompt(), toolCatalog,
                toolCatalog.reviewToolNames(context.allowedBuiltinTools()), context.mcpToolCatalog(), mode,
                fullReview ? SystemPromptAssembler.PromptKind.REVIEW_AGENT
                        : SystemPromptAssembler.PromptKind.WRITER_AGENT);

        if (!enableFormalDecision) {
            return base;
        }

        String prompt = decisionPrompt != null && !decisionPrompt.isBlank()
                ? decisionPrompt : AgentReviewWorkflow.DEFAULT_FORMAL_REVIEW_DECISION_PROMPT;
        return base + "\n\n" + prompt + (mode == ToolingMode.LEGACY
                ? "\nReturn optional severity counts inside the completed review object's classification field."
                : DECISION_FORMAT_INSTRUCTION);
    }

    private String buildKickoffMessage(String prTitle, String prBody, DiffSummary diffSummary,
                                       boolean enableFormalDecision) {
        StringBuilder sb = new StringBuilder();
        sb.append("Please review the following pull request.\n\n");
        sb.append("Title: ").append(prTitle == null ? "(none)" : prTitle).append('\n');
        if (prBody != null && !prBody.isBlank()) {
            sb.append("Description:\n").append(prBody).append('\n');
        }
        sb.append("""
                
                The repository is checked out in your read-only workspace. Use your available \
                read-only tools to inspect the surrounding code before judging the change. \
                You cannot modify the repository.
                
                """);
        sb.append("Changed files (").append(diffSummary.statLine()).append("):\n\n");
        sb.append(diffSummary.fileTable()).append("\n");
        sb.append("""
                To see the diff hunks for a specific file, call the `pr-diff` tool with the \
                file path. To read the full current content of a file, use `cat`. To understand \
                a file's structure before reading it, use `ctags-signatures`.
                
                When you have gathered enough context, reply with your final review (no tool calls). \
                Cover correctness, risks, and concrete suggestions. Anchor each finding to the \
                changed line it concerns using the line numbers from `pr-diff`.""");
        sb.append(aiClient != null && aiClient.supportsNativeTools()
                ? ReviewOutputInstructions.agentic(enableFormalDecision)
                : ReviewOutputInstructions.agenticLegacy(enableFormalDecision));
        return sb.toString();
    }

    private String formatReview(String review) {
        return "## 🤖 Agentic Code Review\n\n" + review
                + "\n\n---\n*Read-only agentic review by AI Git Bot*";
    }

    /**
     * Resolves the PR head branch to clone for review. Prefers the webhook
     * payload; when the head ref is missing (notably {@code issue_comment}-style
     * events with no {@code pull_request.head} block) it authoritatively
     * re-fetches it from the provider API. Returns {@code null} when it cannot be
     * determined — callers MUST skip rather than substitute the repository default
     * branch, so the bot never reviews the wrong branch.
     */
    private String resolveHeadBranch(WebhookPayload payload, String owner, String repo, long prNumber) {
        if (payload.getPullRequest() != null && payload.getPullRequest().getHead() != null
                && payload.getPullRequest().getHead().getRef() != null
                && !payload.getPullRequest().getHead().getRef().isBlank()) {
            return BranchRefs.normalize(payload.getPullRequest().getHead().getRef());
        }
        return fetchHeadBranchFromApi(owner, repo, prNumber);
    }

    @SuppressWarnings("unchecked")
    private String fetchHeadBranchFromApi(String owner, String repo, long prNumber) {
        if (prNumber <= 0) {
            return null;
        }
        try {
            java.util.Map<String, Object> pr = repositoryClient.getPullRequestDetails(owner, repo, prNumber);
            if (pr != null && pr.get("head") instanceof java.util.Map<?, ?> head
                    && ((java.util.Map<String, Object>) head).get("ref") instanceof String ref
                    && !ref.isBlank()) {
                return BranchRefs.normalize(ref);
            }
        } catch (Exception e) {
            log.debug("agentic-review: getPullRequestDetails failed for {}/{}#{}: {}",
                    owner, repo, prNumber, e.getMessage());
        }
        return null;
    }

    private String fetchPinnedFiles(PinnedSourceReader source, List<String> paths) {
        if (paths == null || paths.isEmpty()) return "";
        StringBuilder text = new StringBuilder();
        for (String path : paths) {
            if (text.length() > agentConfig.getMaxFileContentChars()) break;
            text.append("\n--- File: ").append(path).append(" ---\n").append(source.read(path)).append('\n');
        }
        return text.toString();
    }

    private String fetchFiles(String owner, String repo, String ref, List<String> filePaths) {
        if (filePaths == null || filePaths.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int totalChars = 0;
        for (String path : filePaths) {
            if (totalChars > agentConfig.getMaxFileContentChars()) {
                sb.append("\n(File context truncated due to size limits)\n");
                break;
            }
            try {
                String content = repositoryClient.getFileContent(owner, repo, path, ref);
                if (content != null && !content.isEmpty()) {
                    sb.append("\n--- File: ").append(path).append(" ---\n");
                    sb.append(content).append('\n');
                    totalChars += content.length();
                }
            } catch (Exception e) {
                log.debug("Could not fetch file content for {}: {}", path, e.getMessage());
            }
        }
        return sb.toString();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
