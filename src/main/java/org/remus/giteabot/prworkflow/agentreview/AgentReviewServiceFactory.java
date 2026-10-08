package org.remus.giteabot.prworkflow.agentreview;

import org.remus.giteabot.admin.AiClientFactory;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.GiteaClientFactory;
import org.remus.giteabot.agent.session.AgentSessionService;
import org.remus.giteabot.agent.tools.ToolCatalog;
import org.remus.giteabot.agent.validation.ToolExecutionService;
import org.remus.giteabot.agent.validation.WorkspaceService;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.audit.PrAuditEventService;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.config.ReviewChunkingProperties;
import org.remus.giteabot.eventhook.EventHookPublisher;
import org.remus.giteabot.mcp.McpOrchestrationService;
import org.remus.giteabot.mcp.McpToolCatalog;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.review.ReviewSkipService;
import org.remus.giteabot.review.grounding.DoubleCheckService.Budget;
import org.remus.giteabot.review.grounding.DoubleCheckSettings;
import org.remus.giteabot.review.grounding.FindingGroundingService;
import org.remus.giteabot.review.grounding.PriorFindingsService;
import org.remus.giteabot.review.grounding.ThreadActionExecutor;
import org.remus.giteabot.review.state.ReviewRunLeaseService;
import org.remus.giteabot.review.state.ReviewStateService;
import org.remus.giteabot.systemsettings.BotToolSelectionService;
import org.remus.giteabot.systemsettings.McpToolSelectionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
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

    private final ReviewRunLeaseService leases;
    private final ReviewStateService state;
    private final PriorFindingsService priors;
    private final FindingGroundingService grounding;
    private final ThreadActionExecutor threadActions;
    private final ReviewSkipService skipService;
    private final PrAuditEventService audits;
    private final Duration leaseTtl;
    private final Budget doubleCheckBudget;

    public AgentReviewServiceFactory(AiClientFactory aiClientFactory, GiteaClientFactory giteaClientFactory,
                                     McpToolSelectionService mcpToolSelectionService,
                                     McpOrchestrationService mcpOrchestrationService,
                                     BotToolSelectionService botToolSelectionService,
                                     AgentSessionService agentSessionService, ToolExecutionService toolExecutionService,
                                     ToolCatalog toolCatalog, WorkspaceService workspaceService,
                                     AgentConfigProperties agentConfig, EventHookPublisher eventHookPublisher,
                                     ReviewRunLeaseService leases, ReviewStateService state, PriorFindingsService priors,
                                     FindingGroundingService grounding, ThreadActionExecutor threadActions,
                                     ReviewSkipService skipService, PrAuditEventService audits,
                                     @Value("${review-state.lease-ttl:10m}") Duration leaseTtl,
                                     ReviewChunkingProperties chunking) {
        this.aiClientFactory = aiClientFactory;
        this.giteaClientFactory = giteaClientFactory;
        this.mcpToolSelectionService = mcpToolSelectionService;
        this.mcpOrchestrationService = mcpOrchestrationService;
        this.botToolSelectionService = botToolSelectionService;
        this.agentSessionService = agentSessionService;
        this.toolExecutionService = toolExecutionService;
        this.toolCatalog = toolCatalog;
        this.workspaceService = workspaceService;
        this.agentConfig = agentConfig;
        this.eventHookPublisher = eventHookPublisher;
        this.leases = leases;
        this.state = state;
        this.priors = priors;
        this.grounding = grounding;
        this.threadActions = threadActions;
        this.skipService = skipService;
        this.audits = audits;
        this.leaseTtl = leaseTtl;
        this.doubleCheckBudget = new Budget(chunking.getMaxDiffCharsPerChunk(), chunking.getMaxDiffChunks());
    }

    public AgentReviewService create(Bot bot) {
        return create(bot, DoubleCheckSettings.defaults());
    }

    public AgentReviewService create(Bot bot, DoubleCheckSettings settings) {
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
                bot, eventHookPublisher, leases, state, priors, grounding, threadActions, skipService, audits,
                leaseTtl, settings, doubleCheckBudget);
    }
}
