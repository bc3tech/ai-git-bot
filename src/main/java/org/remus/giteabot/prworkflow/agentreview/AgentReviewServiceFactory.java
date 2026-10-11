package org.remus.giteabot.prworkflow.agentreview;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.remus.giteabot.admin.AiClientFactory;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.GiteaClientFactory;
import org.remus.giteabot.agent.session.AgentSessionService;
import org.remus.giteabot.agent.tools.ToolCatalog;
import org.remus.giteabot.agent.validation.ToolExecutionService;
import org.remus.giteabot.agent.validation.WorkspaceService;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.eventhook.EventHookPublisher;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.mcp.McpOrchestrationService;
import org.remus.giteabot.mcp.McpToolCatalog;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.systemsettings.BotToolSelectionService;
import org.remus.giteabot.systemsettings.McpToolSelectionService;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class AgentReviewServiceFactory {

    private final AiClientFactory aiClientFactory;
    private final GiteaClientFactory giteaClientFactory;
    private final McpToolSelectionService mcpToolSelectionService;
    private final McpOrchestrationService mcpOrchestrationService;
    private final BotToolSelectionService botToolSelectionService;
    private final AgentSessionService agentSessionService;
    private final ToolExecutionService toolExecutionService;
    private final ToolCatalog toolCatalog;
    private final WorkspaceService workspaceService;
    private final AgentConfigProperties agentConfig;
    private final EventHookPublisher eventHookPublisher;

    public AgentReviewService create(Bot bot) {
        if (bot.getSystemPrompt() == null) {
            throw new IllegalStateException("Bot must have a system prompt assigned");
        }
        AiClient aiClient = aiClientFactory.getClient(bot.getAiIntegration());
        RepositoryApiClient repoClient = giteaClientFactory.getApiClient(bot.getGitIntegration());
        McpToolCatalog mcpToolCatalog = mcpToolSelectionService.filterCatalogForPrompt(
                bot.getMcpConfiguration(),
                mcpOrchestrationService.discoverTools(bot.getMcpConfiguration()));

        int contextWindowTokens = bot.getAiIntegration() != null
                ? bot.getAiIntegration().getContextWindowTokens() : 200_000;
        AgentReviewContext context = new AgentReviewContext(
                repoClient,
                aiClient,
                bot.getSystemPrompt().getReviewAgentSystemPrompt(),
                bot.getUsername(),
                bot.getMcpConfiguration(),
                mcpToolCatalog,
                botToolSelectionService.allowedBuiltinTools(bot.getToolConfiguration()),
                contextWindowTokens);

        return new AgentReviewService(context, agentSessionService, toolExecutionService,
                toolCatalog, workspaceService, agentConfig, mcpOrchestrationService,
                bot, eventHookPublisher);
    }

    public AgentReviewService.DetailedResult reportSetupFailure(
            Bot bot, WebhookPayload payload, Long runId, boolean clarification) {
        String owner = AgentReviewService.owner(payload);
        String repo = AgentReviewService.repo(payload);
        Long prNumber = AgentReviewService.pullRequestNumber(payload);
        var integration = bot == null ? null : bot.getAiIntegration();
        ReviewDiagnostics.Snapshot failure = new ReviewDiagnostics.Collector(
                null, null, null, runId, owner, repo, prNumber,
                integration == null ? null : integration.getProviderType(),
                integration == null ? null : integration.getId())
                .snapshot(ReviewDiagnostics.Category.SETUP_FAILURE);

        RepositoryApiClient repositoryClient = null;
        try {
            if (bot != null && bot.getGitIntegration() != null) {
                repositoryClient = giteaClientFactory.getApiClient(bot.getGitIntegration());
            }
        } catch (RuntimeException e) {
            log.warn("Could not resolve repository client for agentic-review setup failure");
        }
        ReviewFailureReporter.Delivery delivery = new ReviewFailureReporter().report(
                repositoryClient, owner, repo, prNumber, failure, clarification);
        log.error("Agentic review setup failure: {}; noticeStatus={}; noticeReason={}",
                failure.details(), delivery.status(),
                delivery.reason() == null ? "unavailable" : delivery.reason());
        return new AgentReviewService.DetailedResult(
                AgentReviewService.ReviewResult.FAILED, failure, delivery.status(), delivery.reason());
    }
}
