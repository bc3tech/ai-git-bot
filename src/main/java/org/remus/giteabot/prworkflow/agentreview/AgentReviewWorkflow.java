package org.remus.giteabot.prworkflow.agentreview;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.prworkflow.PrWorkflow;
import org.remus.giteabot.prworkflow.PrWorkflowCategory;
import org.remus.giteabot.prworkflow.PrWorkflowContext;
import org.remus.giteabot.prworkflow.WorkflowParamField;
import org.remus.giteabot.prworkflow.WorkflowParamsSchema;
import org.remus.giteabot.prworkflow.WorkflowResult;
import org.remus.giteabot.prworkflow.WorkflowCancelledException;
import org.remus.giteabot.prworkflow.config.WorkflowSelectionService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class AgentReviewWorkflow implements PrWorkflow {

    public static final String KEY = "agentic-review";

    static final int DEFAULT_MAX_TOOL_ROUNDS = 12;

    static final String DEFAULT_FORMAL_REVIEW_DECISION_PROMPT = """
            # Formal Review Decision

            After writing your review findings, classify them by severity:

            - **BLOCKER** — Critical issues that must be fixed before merging:
              bugs, security vulnerabilities, broken tests, missing error handling,
              or any problem that could cause a production defect.

            - **MEDIUM** — Notable issues that should be addressed but may not block
              merging on their own: significant code-quality concerns, unclear
              naming, missing tests for edge cases, or maintainability risks.

            - **LOW** — Minor issues, style nits, optional suggestions, or small
              observations that should not block merging.

            The application will decide whether to approve or request changes based
            on the configured thresholds for each severity class. Do not emit
            APPROVE, REQUEST_CHANGES, or NONE yourself; only provide the classification
            counts below.""";

    private final AgentReviewServiceFactory serviceFactory;
    private final WorkflowSelectionService selectionService;

    public AgentReviewWorkflow(AgentReviewServiceFactory serviceFactory,
                               @Lazy WorkflowSelectionService selectionService) {
        this.serviceFactory = serviceFactory;
        this.selectionService = selectionService;
    }

    @Override public String key() { return KEY; }
    @Override public String displayName() { return "Agentic PR Review"; }
    @Override public String description() {
        return "Reviews the pull request with an LLM that can iteratively call read-only "
                + "repository and MCP tools to gather context before writing its findings as a "
                + "Markdown comment.";
    }
    @Override public PrWorkflowCategory category() { return PrWorkflowCategory.REVIEW; }

    @Override
    public WorkflowParamsSchema paramsSchema() {
        return WorkflowParamsSchema.of(
                new WorkflowParamField(AgentReviewParam.MAX_TOOL_ROUNDS,
                        "Max tool-exploration rounds",
                        WorkflowParamField.ParamType.INTEGER, false,
                        String.valueOf(DEFAULT_MAX_TOOL_ROUNDS),
                        "Upper bound on how many explore/answer rounds the agent may take."),
                new WorkflowParamField(AgentReviewParam.ENABLE_FORMAL_REVIEW_DECISION,
                        "Enable formal review decision",
                        WorkflowParamField.ParamType.BOOLEAN, false,
                        "false",
                        "When enabled, the bot may post a formal PR review decision."),
                new WorkflowParamField(AgentReviewParam.FORMAL_REVIEW_DECISION_PROMPT,
                        "Approval decision prompt",
                        WorkflowParamField.ParamType.TEXT, false,
                        DEFAULT_FORMAL_REVIEW_DECISION_PROMPT,
                        "Criteria the model uses to classify findings by severity."),
                new WorkflowParamField(AgentReviewParam.BLOCKER_THRESHOLD,
                        "Blocker must be less or equal",
                        WorkflowParamField.ParamType.INTEGER, false,
                        null,
                        "Maximum allowed BLOCKER findings for a formal APPROVE. Leave empty to ignore."),
                new WorkflowParamField(AgentReviewParam.MEDIUM_THRESHOLD,
                        "Medium must be less or equal",
                        WorkflowParamField.ParamType.INTEGER, false,
                        null,
                        "Maximum allowed MEDIUM findings for a formal APPROVE. Leave empty to ignore."),
                new WorkflowParamField(AgentReviewParam.LOW_THRESHOLD,
                        "Low must be less or equal",
                        WorkflowParamField.ParamType.INTEGER, false,
                        null,
                        "Maximum allowed LOW findings for a formal APPROVE. Leave empty to ignore."));
    }

    @Override
    public WorkflowResult run(PrWorkflowContext context) {
        Bot bot = context.bot();
        WebhookPayload payload = context.payload();

        String clarification = context.hint(PrWorkflowContext.HINT_AGENTIC_REVIEW_CLARIFICATION);
        if (clarification != null && !clarification.isBlank()) {
            return doClarification(context, clarification);
        }

        context.requireActive("before running agentic review");

        Map<String, Object> params;
        AgentReviewService service;
        try {
            params = resolveParams(bot);
            service = serviceFactory.create(bot);
        } catch (WorkflowCancelledException e) {
            throw e;
        } catch (RuntimeException e) {
            return complete(context, "agentic-review",
                    serviceFactory.reportSetupFailure(bot, payload, context.runId(), false));
        }
        int maxToolRounds = intParam(params, AgentReviewParam.MAX_TOOL_ROUNDS, DEFAULT_MAX_TOOL_ROUNDS);
        boolean enableFormalDecision = boolParam(params, AgentReviewParam.ENABLE_FORMAL_REVIEW_DECISION, false);
        String decisionPrompt = strParam(params, AgentReviewParam.FORMAL_REVIEW_DECISION_PROMPT,
                DEFAULT_FORMAL_REVIEW_DECISION_PROMPT);
        AgentReviewService.SeverityThresholds thresholds = new AgentReviewService.SeverityThresholds(
                integerParam(params, AgentReviewParam.BLOCKER_THRESHOLD),
                integerParam(params, AgentReviewParam.MEDIUM_THRESHOLD),
                integerParam(params, AgentReviewParam.LOW_THRESHOLD));

        AgentReviewService.DetailedResult reviewed = service.reviewPullRequestDetailed(
                payload, maxToolRounds, enableFormalDecision, decisionPrompt, thresholds,
                context.runId(), context.auditToolCallConsumer());
        return complete(context, "agentic-review", reviewed);
    }

    private WorkflowResult doClarification(PrWorkflowContext context, String userQuestion) {
        Bot bot = context.bot();
        context.requireActive("before running agentic clarification");

        Map<String, Object> params;
        AgentReviewService service;
        try {
            params = resolveParams(bot);
            service = serviceFactory.create(bot);
        } catch (WorkflowCancelledException e) {
            throw e;
        } catch (RuntimeException e) {
            return complete(context, "agentic-clarification",
                    serviceFactory.reportSetupFailure(bot, context.payload(), context.runId(), true));
        }
        int maxToolRounds = intParam(params, AgentReviewParam.MAX_TOOL_ROUNDS, DEFAULT_MAX_TOOL_ROUNDS);

        AgentReviewService.DetailedResult answered = service.answerClarificationDetailed(
                context.payload(), userQuestion, maxToolRounds, context.runId());
        return complete(context, "agentic-clarification", answered);
    }

    private WorkflowResult complete(PrWorkflowContext context, String stepName,
                                    AgentReviewService.DetailedResult detailed) {
        WorkflowResult result = switch (detailed.result()) {
            case POSTED -> WorkflowResult.success(stepName.equals("agentic-review")
                    ? "Agentic review posted" : "Clarification posted");
            case NO_DIFF -> WorkflowResult.skipped(stepName.equals("agentic-review")
                    ? "No diff to review" : "No diff to answer clarification");
            case FAILED -> WorkflowResult.failed(failureSummary(stepName, detailed));
        };
        String excerpt = detailed.failure() == null ? result.summary()
                : detailed.failure().details() + "; noticeStatus=" + detailed.noticeStatus()
                + "; noticeReason=" + (detailed.noticeReason() == null
                ? "unavailable" : detailed.noticeReason());
        try {
            context.appendStep(stepName, excerpt);
        } catch (RuntimeException e) {
            log.warn("Could not persist agentic-review diagnostics; preserving run result: {}", result.summary());
        }
        return result;
    }

    private String failureSummary(String stepName, AgentReviewService.DetailedResult detailed) {
        String subject = detailed.failure() == null ? "unknown-execution-failure"
                : detailed.failure().summary();
        String prefix = stepName.equals("agentic-review") ? "Agentic review failed" : "Clarification failed";
        return prefix + " (" + subject + "; notice "
                + detailed.noticeStatus().name().toLowerCase() + ")";
    }

    private Map<String, Object> resolveParams(Bot bot) {
        if (bot.getWorkflowConfiguration() == null) return Map.of();
        return selectionService.resolveParams(bot.getWorkflowConfiguration().getId(), KEY);
    }

    private int intParam(Map<String, Object> params, AgentReviewParam name, int fallback) {
        Object raw = params.get(name.key());
        if (raw instanceof Number n) return n.intValue();
        if (raw == null) return fallback;
        try { return Integer.parseInt(raw.toString().trim()); }
        catch (NumberFormatException e) { return fallback; }
    }

    private Integer integerParam(Map<String, Object> params, AgentReviewParam name) {
        Object raw = params.get(name.key());
        if (raw instanceof Number n) return n.intValue();
        if (raw == null) return null;
        try { return Integer.parseInt(raw.toString().trim()); }
        catch (NumberFormatException e) { return null; }
    }

    private boolean boolParam(Map<String, Object> params, AgentReviewParam name, boolean fallback) {
        Object raw = params.get(name.key());
        if (raw instanceof Boolean b) return b;
        if (raw == null) return fallback;
        String s = raw.toString().trim();
        return "true".equalsIgnoreCase(s) || "1".equals(s);
    }

    private String strParam(Map<String, Object> params, AgentReviewParam name, String fallback) {
        Object raw = params.get(name.key());
        if (raw instanceof String s && !s.isBlank()) return s;
        return fallback;
    }
}
