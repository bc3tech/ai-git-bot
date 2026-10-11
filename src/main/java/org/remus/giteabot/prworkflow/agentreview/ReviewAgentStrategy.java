package org.remus.giteabot.prworkflow.agentreview;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.issueimpl.AiResponseParser;
import org.remus.giteabot.agent.loop.AgentRunContext;
import org.remus.giteabot.agent.loop.AgentBudget;
import org.remus.giteabot.agent.loop.ContextBudgetExceededException;
import org.remus.giteabot.agent.loop.AgentStrategy;
import org.remus.giteabot.agent.loop.LoopOutcome;
import org.remus.giteabot.agent.loop.StepDecision;
import org.remus.giteabot.agent.loop.ToolingMode;
import org.remus.giteabot.agent.model.ImplementationPlan;
import org.remus.giteabot.agent.shared.BranchSwitcher;
import org.remus.giteabot.agent.shared.McpTools;
import org.remus.giteabot.agent.shared.ToolFailures;
import org.remus.giteabot.agent.tools.AgentToolRouter;
import org.remus.giteabot.agent.tools.ToolCallContext;
import org.remus.giteabot.agent.tools.ToolCatalog;
import org.remus.giteabot.agent.validation.ToolResult;
import org.remus.giteabot.ai.ChatTurn;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.ai.AiMessage;
import org.remus.giteabot.ai.StopReason;
import org.remus.giteabot.ai.ToolCall;
import org.remus.giteabot.ai.ToolDescriptor;
import org.remus.giteabot.mcp.McpToolCatalog;
import org.remus.giteabot.review.ReviewFence;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Read-only {@link AgentStrategy} powering the agentic PR-review workflow.
 *
 * <p>The strategy is deliberately <em>read-only</em>: it advertises the
 * {@link ToolCatalog.Role#REVIEW} descriptor surface (repository-exploration
 * tools such as {@code cat}, {@code rg}, {@code find}, {@code tree},
 * {@code git-log}, {@code git-blame}, the read-only issue helpers and MCP
 * tools) and routes execution through {@link AgentToolRouter.Mode#REVIEW},
 * which rejects file-mutation, validation/build and branch-switching built-ins.
 * Operators must select only read-only MCP tools for the review configuration.</p>
 *
 * <p>Two transports are supported, mirroring
 * {@link org.remus.giteabot.agent.issueimpl.CodingAgentStrategy}: in
 * {@link ToolingMode#NATIVE} mode tool calls arrive as a structured
 * {@link ChatTurn} and results are fed back as {@code tool}-role messages; when
 * the client cannot do native function calling the loop falls back to the
 * legacy JSON protocol ({@code requestFiles}/{@code requestTools}/
 * {@code runTools}) parsed via {@link AiResponseParser}. In both cases the loop
 * accepts a non-empty final text without tool/context requests. In both modes
 * its stop reason must also indicate completion. Exhausted budgets and empty
 * or truncated replies are failures, never fallback reviews.</p>
 */
@Slf4j
public final class ReviewAgentStrategy implements AgentStrategy {

    /** Fetches repository file contents for the legacy {@code requestFiles} protocol. */
    @FunctionalInterface
    public interface FileFetcher {
        String fetch(String owner, String repo, String ref, List<String> files);
    }

    /** Singleton pinned reads with authoritative acquisition metadata, including verified empty files. */
    @FunctionalInterface
    public interface FileEvidenceFetcher {
        FileEvidence fetch(String owner, String repo, String ref, String path);
    }

    /**
     * Raw, unnumbered source lines. {@code complete} means the whole source was acquired;
     * {@code totalLines} is nullable and must come from verified source metadata, not an estimate.
     * An incomplete payload's unterminated last line is conservatively treated as a fragment.
     */
    public record FileEvidence(boolean success, boolean complete, String content,
                               int startLine, Integer totalLines) {
        public FileEvidence {
            if (complete && content == null) {
                throw new IllegalArgumentException("Complete evidence requires verified content");
            }
            content = content == null ? "" : content;
            if (startLine < 1 || (totalLines != null && totalLines < 0)
                    || (!success && complete) || (complete && startLine != 1)) {
                throw new IllegalArgumentException("Invalid file evidence metadata");
            }
            if (!success) content = "";
            int count = lineCount(content);
            if (complete && totalLines == null) totalLines = count;
            if (totalLines != null && ((complete && count != totalLines)
                    || (count > 0 && (long) startLine + count - 1 > totalLines))) {
                throw new IllegalArgumentException("File evidence exceeds verified total coverage");
            }
        }

        public static FileEvidence complete(String content) {
            if (content == null) return failed();
            return new FileEvidence(true, true, content, 1, lineCount(content));
        }

        public static FileEvidence failed() {
            return new FileEvidence(false, false, "", 1, null);
        }

        private static int lineCount(String content) {
            if (content.isEmpty()) return 0;
            String[] lines = content.split("\\r\\n|\\n|\\r", -1);
            return lines.length - (lines[lines.length - 1].isEmpty() ? 1 : 0);
        }
    }

    private final ReviewFence fence;
    private final String systemPrompt;
    private final AgentToolRouter toolRouter;
    private final ToolCatalog catalog;
    private final McpToolCatalog mcpToolCatalog;
    private final Set<String> allowedBuiltinTools;

    // Legacy-mode collaborators (mirror CodingAgentStrategy).
    private final AiResponseParser responseParser;
    private final FileFetcher fileFetcher;
    private final int maxContextRounds;
    private final ReviewDiagnostics.Collector diagnostics;

    /** Read-only context-fetch rounds consumed in legacy mode. */
    private int contextRounds = 0;
    private LegacyReviewEvidence evidence;
    private FileEvidenceFetcher fileEvidenceFetcher;
    private int maxEvidenceChars;
    private String initialTask;
    private String immutableRef;
    private int consecutiveNonProgress;
    private int previousNonProgress;
    private boolean contextPending;
    private Map<LegacyReviewEvidence.Key, String> previousAvailability = Map.of();
    private Set<LegacyReviewEvidence.Key> executedThisTurn = Set.of();
    private AgentBudget preparationBudget;
    private AiClient preparationClient;
    private List<ToolDescriptor> preparationTools = List.of();
    private String preparationSystemPrompt;
    private boolean aggressivePreparation;

    /** Default-off: enable only for full legacy reviews, never clarification/native exchanges. */
    public ReviewAgentStrategy enableLegacyRecovery(int maxEvidenceChars) {
        if (maxEvidenceChars < 0) throw new IllegalArgumentException("Negative evidence budget");
        this.maxEvidenceChars = maxEvidenceChars;
        this.evidence = new LegacyReviewEvidence();
        return this;
    }

    /** Required for verified legacy file evidence; old String fetchers cannot prove acquisition. */
    public ReviewAgentStrategy withFileEvidenceFetcher(FileEvidenceFetcher fetcher) {
        this.fileEvidenceFetcher = java.util.Objects.requireNonNull(fetcher);
        return this;
    }

    public List<String> coverageNotes() {
        return evidence == null ? List.of() : evidence.coverageNotes();
    }

    @Override
    public boolean usesRequestPreparation() {
        return evidence != null;
    }

    @Override
    public String prepareRequest(AgentRunContext ctx, List<AiMessage> history, String currentMessage,
                                 String systemPrompt, List<ToolDescriptor> tools,
                                 AgentBudget budget, AiClient client, boolean aggressive) {
        if (evidence == null) return currentMessage;
        fence.requireActive("before preparing legacy review request");
        if (initialTask == null) initialTask = currentMessage == null ? "" : currentMessage;
        pinRef(ctx);
        preparationBudget = budget;
        preparationClient = client;
        preparationTools = tools;
        preparationSystemPrompt = systemPrompt;
        aggressivePreparation = aggressive;
        // Legacy source payloads are user messages, not tool messages. Keep exactly the active
        // task capsule and regenerate all availability/acknowledgements after final selection.
        history.clear();
        String mandatory = continuation("");
        if (!requestFits(mandatory)) {
            throw new ContextBudgetExceededException("Mandatory legacy review context exceeds request budget");
        }
        selectEvidence();
        updateProgress();
        if (consecutiveNonProgress >= 2) throw new StalledReviewContextException();
        return continuation(evidence.snapshot());
    }

    private void pinRef(AgentRunContext ctx) {
        if (immutableRef == null) immutableRef = ctx.baseBranch();
        if (!java.util.Objects.equals(immutableRef, ctx.baseBranch())) {
            throw new IllegalStateException("Legacy review revision changed during evidence recovery");
        }
    }

    private boolean requestFits(String current) {
        if (preparationBudget == null) return true;
        if (current.length() > preparationBudget.maxHistoryChars()) return false;
        long threshold = (long) Math.floor(preparationBudget.contextWindowTokens()
                * preparationBudget.proactiveCompactionThreshold());
        long inputLimit = threshold - Math.max(0, preparationBudget.maxTokensPerCall());
        return inputLimit >= 0 && preparationClient.estimateRequestTokens(List.of(), current,
                preparationTools, preparationSystemPrompt) <= inputLimit;
    }

    private void selectEvidence() {
        int cap = aggressivePreparation ? maxEvidenceChars / 2 : maxEvidenceChars;
        evidence.select(cap, snapshot -> requestFits(continuation(snapshot)));
        while (!requestFits(continuation(evidence.snapshot())) && cap > 0) {
            cap /= 2;
            evidence.select(cap, snapshot -> requestFits(continuation(snapshot)));
        }
        if (!requestFits(continuation(evidence.snapshot()))) {
            throw new ContextBudgetExceededException("Mandatory legacy review inventory exceeds request budget");
        }
    }

    private void updateProgress() {
        if (contextPending) {
            consecutiveNonProgress = evidence.delivered(executedThisTurn, previousAvailability)
                    ? 0 : previousNonProgress + 1;
        }
    }

    private String continuation(String snapshot) {
        String task = initialTask == null ? "" : initialTask;
        String recovery = """

                Recovery policy: if no new usable evidence was delivered, do not repeat retained reads.
                Request a narrower omitted region or finish with the evidence actually available.
                Only one recovery opportunity is allowed; the next non-progress response terminates review.
                Consecutive non-progress context turns: """ + consecutiveNonProgress + "\n";
        return "Active initial review task:\n" + task
                + "\n\nImmutable source ref: " + (immutableRef == null ? "(unavailable)" : immutableRef)
                + "\n\n" + evidence.inventory()
                + "\n\nCurrent evidence snapshot (only blocks below are available):\n" + snapshot
                + "\nUnavailable/omitted sources are not acknowledged as retained evidence. "
                + "A retained excerpt is not a complete file. Use narrower same-ref reads for omitted regions."
                + recovery
                + "\nContinue the read-only review. Reply with one required JSON envelope: "
                + "exploration requests OR the completed review, never both.";
    }

    public ReviewAgentStrategy(String systemPrompt,
                               AgentToolRouter toolRouter,
                               ToolCatalog catalog,
                               McpToolCatalog mcpToolCatalog,
                               Set<String> allowedBuiltinTools,
                               AiResponseParser responseParser,
                               BranchSwitcher branchSwitcher,
                               FileFetcher fileFetcher,
                               int maxContextRounds) {
        this(systemPrompt, toolRouter, catalog, mcpToolCatalog, allowedBuiltinTools,
                responseParser, branchSwitcher, fileFetcher, maxContextRounds, ReviewFence.NONE, null);
    }

    public ReviewAgentStrategy(String systemPrompt,
                               AgentToolRouter toolRouter,
                               ToolCatalog catalog,
                               McpToolCatalog mcpToolCatalog,
                               Set<String> allowedBuiltinTools,
                               AiResponseParser responseParser,
                               BranchSwitcher branchSwitcher,
                               FileFetcher fileFetcher,
                               int maxContextRounds,
                               ReviewDiagnostics.Collector diagnostics) {
        this(systemPrompt, toolRouter, catalog, mcpToolCatalog, allowedBuiltinTools,
                responseParser, branchSwitcher, fileFetcher, maxContextRounds, ReviewFence.NONE, diagnostics);
    }

    public ReviewAgentStrategy(String systemPrompt, AgentToolRouter toolRouter, ToolCatalog catalog,
                               McpToolCatalog mcpToolCatalog, Set<String> allowedBuiltinTools,
                               AiResponseParser responseParser, BranchSwitcher branchSwitcher,
                               FileFetcher fileFetcher, int maxContextRounds, ReviewFence fence) {
        this(systemPrompt, toolRouter, catalog, mcpToolCatalog, allowedBuiltinTools, responseParser,
                branchSwitcher, fileFetcher, maxContextRounds, fence, null);
    }

    public ReviewAgentStrategy(String systemPrompt, AgentToolRouter toolRouter, ToolCatalog catalog,
                               McpToolCatalog mcpToolCatalog, Set<String> allowedBuiltinTools,
                               AiResponseParser responseParser, BranchSwitcher branchSwitcher,
                               FileFetcher fileFetcher, int maxContextRounds, ReviewFence fence,
                               ReviewDiagnostics.Collector diagnostics) {
        this.fence = fence;
        this.systemPrompt = systemPrompt;
        this.toolRouter = toolRouter;
        this.catalog = catalog;
        this.mcpToolCatalog = mcpToolCatalog != null ? mcpToolCatalog : McpToolCatalog.empty();
        this.allowedBuiltinTools = allowedBuiltinTools;
        this.responseParser = responseParser;
        // Retain the constructor parameter for callers; reviews never switch branches.
        this.fileFetcher = fileFetcher;
        this.maxContextRounds = Math.max(1, maxContextRounds);
        this.diagnostics = diagnostics;
    }

    @Override
    public String systemPrompt() {
        return systemPrompt;
    }

    @Override
    public ToolingMode preferredToolMode() {
        return ToolingMode.NATIVE;
    }

    /** Read-only review toolbox plus the explicitly selected MCP catalog. */
    @Override
    public List<ToolDescriptor> toolDescriptors() {
        return catalog.nativeDescriptors(ToolCatalog.Role.REVIEW, mcpToolCatalog, allowedBuiltinTools);
    }

    @Override
    public StepDecision step(AgentRunContext ctx, ChatTurn turn, int round) {
        fence.requireActive("before agentic review turn");
        StopReason expected = turn.hasToolCalls() ? StopReason.TOOL_USE : StopReason.END_TURN;
        if (turn.stopReason() != expected) {
            return incompleteTurn(ctx, turn);
        }
        // Only a completed, non-empty text turn may become the final review.
        if (!turn.hasToolCalls()) {
            return finish(ctx, turn.assistantText());
        }

        // Execute every requested (read-only) tool and feed the results back.
        List<ImplementationPlan.ToolRequest> requests = new ArrayList<>();
        for (ToolCall call : turn.toolCalls()) {
            requests.add(toRequest(call));
        }
        if (diagnostics != null) {
            diagnostics.contextRoundExecuted();
        }
        List<ToolResult> results = executeAll(ctx, requests, turn.toolCalls(), round);
        List<StepDecision.ToolCallResult> packaged = packageResults(requests, results, turn.toolCalls());
        if (diagnostics != null) {
            diagnostics.addContextCharacters(packaged.stream()
                    .mapToInt(result -> result.resultText() == null ? 0 : result.resultText().length())
                    .sum());
        }
        return new StepDecision.ContinueWithToolResults(packaged, null);
    }

    @Override
    public StepDecision stepLegacy(AgentRunContext ctx, ChatTurn turn, int round) {
        // Reject any turn carrying tool calls, even with END_TURN: the legacy
        // JSON protocol has no native-call envelope, so such a mix can only be
        // provider metadata leaking into a text turn — never accept its text.
        if (turn.stopReason() != StopReason.END_TURN || turn.hasToolCalls()) {
            return incompleteTurn(ctx, turn);
        }
        return step(ctx, turn.assistantText(), round);
    }

    private StepDecision incompleteTurn(AgentRunContext ctx, ChatTurn turn) {
        ReviewDiagnostics.Category category = switch (turn.stopReason()) {
            case MAX_TOKENS -> ReviewDiagnostics.Category.OUTPUT_TOKEN_LIMIT;
            case OTHER -> ReviewDiagnostics.Category.UNKNOWN_COMPLETION;
            default -> ReviewDiagnostics.Category.INCOMPLETE_COMPLETION;
        };
        log.warn("Agentic review stopped before completion for PR #{}: {}", ctx.issueNumber(),
                turn.stopReason());
        return new StepDecision.Finish(LoopOutcome.fail(ctx.baseBranch(), failureSnapshot(category)));
    }

    @Override
    public StepDecision step(AgentRunContext ctx, String aiResponse, int round) {
        fence.requireActive("before legacy agentic review turn");
        if (evidence != null) evidence.commitDelivery();
        ImplementationPlan plan = responseParser.parseAiResponse(aiResponse);

        // Collect legacy tool requests (requestTools/runTools) and explicit file requests.
        // The REVIEW router rejects tools not explicitly enabled for read-only review.
        List<ImplementationPlan.ToolRequest> toolRequests = new ArrayList<>();
        List<String> requestFiles = null;
        if (plan != null) {
            if (plan.getRequestTools() != null) {
                toolRequests.addAll(plan.getRequestTools());
            }
            if (plan.hasToolRequest()) {
                toolRequests.addAll(plan.getEffectiveToolRequests());
            }
            requestFiles = plan.getRequestFiles();
        }
        boolean wantsContext = !toolRequests.isEmpty()
                || (requestFiles != null && !requestFiles.isEmpty());

        // Gather the requested read-only context and ask the model to continue.
        if (wantsContext && contextRounds < maxContextRounds) {
            contextRounds++;
            if (diagnostics != null) {
                diagnostics.contextRoundExecuted();
            }
            log.info("Agentic review (legacy) gathering context for PR #{} (round {}/{})",
                    ctx.issueNumber(), contextRounds, maxContextRounds);

            if (diagnostics != null) {
                diagnostics.observeLegacyRequests(toolRequests, requestFiles, ctx.baseBranch(), round);
            }

            if (evidence != null) {
                return gatherRecoverableContext(ctx, requestFiles, toolRequests);
            }

            // Review context stays on the original revision; all tools pass through the review policy.
            String context = gatherContext(ctx, requestFiles, toolRequests);
            if (diagnostics != null) {
                diagnostics.addContextCharacters(context.length());
            }
            return new StepDecision.Continue(
                    "Here is the requested repository context:\n" + context
                            + "\n\nContinue your review. When you have gathered enough context, reply with "
                            + "the final review in the required JSON envelope (no further tool requests).");
        }

        if (wantsContext) {
            log.warn("Agentic review (legacy) hit the context-round cap ({}) for PR #{} before completion",
                    maxContextRounds, ctx.issueNumber());
            if (diagnostics != null) {
                diagnostics.observeLegacyRequests(toolRequests, requestFiles, ctx.baseBranch(), round);
            }
            return new StepDecision.Finish(LoopOutcome.fail(ctx.baseBranch(),
                    failureSnapshot(ReviewDiagnostics.Category.CONTEXT_ROUND_LIMIT)));
        }
        return finish(ctx, finalReviewText(plan, aiResponse));
    }

    @Override
    public LoopOutcome onBudgetExhausted(AgentRunContext ctx) {
        log.warn("Agentic review loop exhausted its round budget for PR #{}", ctx.issueNumber());
        return LoopOutcome.fail(ctx.baseBranch(),
                failureSnapshot(ReviewDiagnostics.Category.LOOP_ROUND_LIMIT));
    }

    // ---------------------------------------------------------------------

    private StepDecision finish(AgentRunContext ctx, String review) {
        if (review == null || review.isBlank()) {
            return new StepDecision.Finish(LoopOutcome.fail(ctx.baseBranch(),
                    failureSnapshot(ReviewDiagnostics.Category.EMPTY_FEEDBACK)));
        }
        return new StepDecision.Finish(LoopOutcome.success(ctx.baseBranch(), review));
    }

    private ReviewDiagnostics.Snapshot failureSnapshot(ReviewDiagnostics.Category category) {
        if (diagnostics != null) {
            return diagnostics.snapshot(category);
        }
        return new ReviewDiagnostics.Collector(maxContextRounds, null, null, null,
                null, null, null, null, null).snapshot(category);
    }

    /**
     * Determines the final review text from a legacy turn. A parseable plan
     * without further requests yields its {@code summary}; otherwise the raw
     * (plain-text) assistant response is the review.
     */
    private String finalReviewText(ImplementationPlan plan, String aiResponse) {
        if (plan == null || org.remus.giteabot.review.ReviewOutputParser.parse(aiResponse).document().structured()) {
            return aiResponse;
        }
        String nonJson = responseParser.extractNonJsonResponse(aiResponse);
        if (nonJson != null && !nonJson.isBlank()) {
            return nonJson;
        }
        return (plan.getSummary() != null && !plan.getSummary().isBlank())
                ? plan.getSummary() : aiResponse;
    }

    /** Renders requested files + read-only tool results into a single context block. */
    private String gatherContext(AgentRunContext ctx, List<String> files,
                                 List<ImplementationPlan.ToolRequest> toolRequests) {
        StringBuilder sb = new StringBuilder();
        if (files != null && !files.isEmpty() && fileFetcher != null) {
            if (diagnostics != null) {
                files.forEach(ignored -> diagnostics.requestExecuted());
            }
            String fetched = fileFetcher.fetch(ctx.owner(), ctx.repo(), ctx.baseBranch(), files);
            if (fetched != null && !fetched.isBlank()) {
                sb.append("## Requested Files\n").append(fetched);
            }

        }
        if (toolRequests != null && !toolRequests.isEmpty()) {
            List<ToolResult> results = executeAll(ctx, toolRequests);
            StringBuilder tools = new StringBuilder();
            for (int i = 0; i < toolRequests.size(); i++) {
                ImplementationPlan.ToolRequest req = toolRequests.get(i);
                ToolResult result = results.get(i);
                tools.append("### `").append(req.getTool());
                if (req.getArgs() != null && !req.getArgs().isEmpty()) {
                    tools.append(' ').append(String.join(" ", req.getArgs()));
                }
                tools.append("`\n");
                if (result.success()) {
                    String output = result.output();
                    tools.append(output == null || output.isBlank() ? "(no output)" : output).append("\n\n");
                } else {
                    tools.append("Failed: ").append(ToolFailures.describe(result)).append("\n\n");
                }
            }
            if (!tools.isEmpty()) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append("## Repository Tool Results\n").append(tools.toString().strip());
            }
        }
        return sb.isEmpty() ? "No additional repository context could be retrieved." : sb.toString();
    }

    private StepDecision gatherRecoverableContext(AgentRunContext ctx, List<String> files,
                                                  List<ImplementationPlan.ToolRequest> tools) {
        pinRef(ctx);
        previousAvailability = evidence.availability();
        previousNonProgress = consecutiveNonProgress;
        executedThisTurn = new HashSet<>();
        contextPending = true;
        if (files != null) {
            for (String path : files) {
                fence.requireActive("before agentic review file read");
                LegacyReviewEvidence.Key key = LegacyReviewEvidence.Key.file(immutableRef, path);
                evidence.requested(key);
                if (path == null || path.isBlank()) {
                    evidence.acquired(key, FileEvidence.failed());
                    continue;
                }
                if (evidence.available(key)) {
                    if (diagnostics != null) diagnostics.requestSuppressed();
                    continue;
                }
                if (diagnostics != null && (fileEvidenceFetcher != null || fileFetcher != null)) {
                    diagnostics.requestExecuted();
                }
                executedThisTurn.add(key);
                // An untyped string cannot establish success, failure, or full-file coverage.
                // Existing callers keep their old behavior unless they explicitly enable recovery.
                FileEvidence fetched;
                if (fileEvidenceFetcher != null) {
                    fetched = fileEvidenceFetcher.fetch(ctx.owner(), ctx.repo(), immutableRef, path);
                } else {
                    if (fileFetcher != null) {
                        fileFetcher.fetch(ctx.owner(), ctx.repo(), immutableRef, List.of(path));
                    }
                    fetched = FileEvidence.failed();
                }
                fence.requireActive("after agentic review file read");
                evidence.acquired(key, fetched == null ? FileEvidence.failed() : fetched);
                selectEvidence();
            }
        }
        for (ImplementationPlan.ToolRequest tool : tools) {
            fence.requireActive("before agentic review evidence tool");
            LegacyReviewEvidence.Key key = LegacyReviewEvidence.Key.tool(immutableRef, tool);
            evidence.requested(key);
            if (evidence.available(key)) {
                if (diagnostics != null) diagnostics.requestSuppressed();
                continue;
            }
            executedThisTurn.add(key);
            ToolResult result = executeAll(ctx, List.of(tool)).getFirst();
            fence.requireActive("after agentic review evidence tool");
            evidence.acquired(key, LegacyReviewEvidence.toolEvidence(key, result));
            selectEvidence();
        }
        selectEvidence();
        updateProgress();
        if (consecutiveNonProgress >= 2) {
            return new StepDecision.Finish(LoopOutcome.fail(ctx.baseBranch(),
                    failureSnapshot(ReviewDiagnostics.Category.STALLED_CONTEXT)));
        }
        String next = continuation(evidence.snapshot());
        if (diagnostics != null) diagnostics.addContextCharacters(evidence.snapshot().length());
        return new StepDecision.Continue(next);
    }

    private List<ToolResult> executeAll(AgentRunContext ctx, List<ImplementationPlan.ToolRequest> requests) {
        return executeAll(ctx, requests, null, 0);
    }

    private List<ToolResult> executeAll(AgentRunContext ctx, List<ImplementationPlan.ToolRequest> requests,
                                        List<ToolCall> nativeCalls, int round) {
        List<ToolResult> results = new ArrayList<>(requests.size());
        for (int i = 0; i < requests.size(); i++) {
            ImplementationPlan.ToolRequest req = requests.get(i);
            fence.requireActive("before agentic review tool " + req.getTool());
            if (diagnostics != null && nativeCalls != null) {
                diagnostics.observeNativeRequests(List.of(nativeCalls.get(i)), ctx.baseBranch(), round);
            }
            ToolResult result = toolRouter.execute(AgentToolRouter.Mode.REVIEW,
                    new ToolCallContext(ctx.owner(), ctx.repo(), ctx.issueNumber(),
                            ctx.workspaceDir(), req, ctx.diffSummary()));
            if (diagnostics != null) diagnostics.requestExecuted();
            results.add(result);
        }
        return results;
    }

    private List<StepDecision.ToolCallResult> packageResults(List<ImplementationPlan.ToolRequest> requests,
                                                             List<ToolResult> results,
                                                             List<ToolCall> originalCalls) {
        Map<String, String> byId = new HashMap<>();
        for (int i = 0; i < requests.size(); i++) {
            byId.put(requests.get(i).getId(), results.get(i).formatForAi());
        }
        List<StepDecision.ToolCallResult> out = new ArrayList<>(originalCalls.size());
        for (ToolCall call : originalCalls) {
            out.add(new StepDecision.ToolCallResult(call.id(),
                    byId.getOrDefault(call.id(), "[no result]")));
        }
        return out;
    }

    /**
     * Converts a native {@link ToolCall} into the positional-args
     * {@link ImplementationPlan.ToolRequest} that {@link AgentToolRouter}
     * expects. Advertised schemas come from the REVIEW toolbox and selected MCP catalog.
     */
    private ImplementationPlan.ToolRequest toRequest(ToolCall call) {
        List<String> args = new ArrayList<>();
        JsonNode root = call.args();
        if (root != null && root.isObject()) {
            if (McpTools.looksLikeMcpTool(call.name())) {
                // MCP: pass the whole arguments object through as a single JSON blob.
                args.add(root.toString());
            } else {
                JsonNode varargs = root.get("args");
                if (varargs != null && varargs.isArray()) {
                    varargs.forEach(node -> args.add(asString(node)));
                } else {
                    addIfPresent(root, "path", args);
                    addIfPresent(root, "branch", args);
                    addIfPresent(root, "startLine", args);
                    addIfPresent(root, "endLine", args);
                    if (args.isEmpty() && !root.isEmpty()) {
                        args.add(root.toString());
                    }
                }
            }
        }
        return ImplementationPlan.ToolRequest.builder()
                .id(call.id() == null || call.id().isBlank() ? UUID.randomUUID().toString() : call.id())
                .tool(call.name())
                .args(args)
                .build();
    }

    private static void addIfPresent(JsonNode root, String field, List<String> out) {
        JsonNode v = root.get(field);
        if (v != null && !v.isMissingNode() && !v.isNull()) {
            out.add(asString(v));
        }
    }

    private static String asString(JsonNode node) {
        return node.isString() ? node.asString() : node.toString();
    }
}
