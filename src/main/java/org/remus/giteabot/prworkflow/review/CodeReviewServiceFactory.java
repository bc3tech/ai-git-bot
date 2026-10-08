package org.remus.giteabot.prworkflow.review;

import org.remus.giteabot.admin.AiClientFactory;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.config.ReviewConfigProperties;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.review.CodeReviewService;
import org.remus.giteabot.review.grounding.DoubleCheckSettings;
import org.remus.giteabot.session.SessionService;
import org.remus.giteabot.audit.PrAuditEventService;
import org.remus.giteabot.review.ReviewSkipService;
import org.remus.giteabot.review.grounding.FindingGroundingService;
import org.remus.giteabot.review.grounding.PriorFindingsService;
import org.remus.giteabot.review.grounding.ThreadActionExecutor;
import org.remus.giteabot.review.state.ReviewRunLeaseService;
import org.remus.giteabot.review.state.ReviewStateService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Factory for per-bot {@link CodeReviewService} instances.
 *
 * <p>Extracted from {@link org.remus.giteabot.admin.BotWebhookService} in
 * milestone M1 so both the new {@link ReviewWorkflow} and the legacy
 * code-review handlers (bot commands, inline review replies, …) can share a
 * single construction path. Standard reviews receive the grounding and run-lease
 * services; conversational handlers retain their session-based behavior.</p>
 *
 * <p>All callers must use the full variant that accepts explicit chunking
 * parameters. The chunking values are sourced from workflow configuration
 * (or {@code review.chunking.*} application properties as a fallback) and
 * passed through by {@link ReviewWorkflow#run}.</p>
 */
@Component
public class CodeReviewServiceFactory {

    private final AiClientFactory aiClientFactory;
    private final SessionService sessionService;
    private final ReviewConfigProperties reviewConfig;
    private final ReviewRunLeaseService leases;
    private final ReviewStateService state;
    private final PriorFindingsService priors;
    private final FindingGroundingService grounding;
    private final ThreadActionExecutor threadActions;
    private final PrAuditEventService audit;
    private final ReviewSkipService skip;
    private final Duration leaseTtl;

    public CodeReviewServiceFactory(AiClientFactory aiClientFactory, SessionService sessionService,
                                    ReviewConfigProperties reviewConfig, ReviewRunLeaseService leases,
                                    ReviewStateService state, PriorFindingsService priors,
                                    FindingGroundingService grounding, ThreadActionExecutor threadActions,
                                    PrAuditEventService audit, ReviewSkipService skip,
                                    @Value("${review-state.lease-ttl:10m}") Duration leaseTtl) {
        this.aiClientFactory = aiClientFactory;
        this.sessionService = sessionService;
        this.reviewConfig = reviewConfig;
        this.leases = leases;
        this.state = state;
        this.priors = priors;
        this.grounding = grounding;
        this.threadActions = threadActions;
        this.audit = audit;
        this.skip = skip;
        this.leaseTtl = leaseTtl;
    }

    /**
     * Builds a fresh {@link CodeReviewService} for the given bot, using the
     * bot's configured AI integration, the supplied repository client, and
     * explicit diff-chunking parameters sourced from workflow configuration.
     */
    public CodeReviewService create(Bot bot, RepositoryApiClient repoClient,
                                    int maxDiffCharsPerChunk, int maxDiffChunks,
                                    int retryTruncatedChunkChars, String excludedFilePatterns) {
        return create(bot, repoClient, maxDiffCharsPerChunk, maxDiffChunks,
                retryTruncatedChunkChars, excludedFilePatterns, DoubleCheckSettings.defaults());
    }

    public CodeReviewService create(Bot bot, RepositoryApiClient repoClient,
                                    int maxDiffCharsPerChunk, int maxDiffChunks,
                                    int retryTruncatedChunkChars, String excludedFilePatterns,
                                    DoubleCheckSettings doubleCheckSettings) {
        if (bot.getSystemPrompt() == null) {
            throw new IllegalStateException("Bot must have a system prompt assigned");
        }
        if (bot.getSystemPrompt().getId() == null) {
            throw new IllegalStateException("Bot system prompt must be persisted");
        }
        AiClient aiClient = aiClientFactory.getClient(bot.getAiIntegration());
        String sessionPromptKey = "system-prompt:" + bot.getSystemPrompt().getId();
        return new CodeReviewService(repoClient, aiClient, sessionService, bot.getUsername(),
                reviewConfig, sessionPromptKey, bot.getSystemPrompt().getReviewSystemPrompt(),
                maxDiffCharsPerChunk, maxDiffChunks, retryTruncatedChunkChars, excludedFilePatterns, doubleCheckSettings,
                bot.getId(), leases, state, priors, grounding, threadActions, audit, skip, leaseTtl);
    }
}
