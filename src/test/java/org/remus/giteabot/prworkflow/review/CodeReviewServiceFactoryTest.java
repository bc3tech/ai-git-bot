package org.remus.giteabot.prworkflow.review;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.admin.AiClientFactory;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.config.ReviewConfigProperties;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.review.grounding.DoubleCheckSettings;
import org.remus.giteabot.review.grounding.FindingGroundingService;
import org.remus.giteabot.review.grounding.PriorFindingsService;
import org.remus.giteabot.review.grounding.ThreadActionExecutor;
import org.remus.giteabot.review.state.ReviewRunLeaseService;
import org.remus.giteabot.review.state.ReviewStateService;
import org.remus.giteabot.review.ReviewSkipService;
import org.remus.giteabot.audit.PrAuditEventService;
import java.time.Duration;
import org.remus.giteabot.session.SessionService;
import org.remus.giteabot.systemsettings.SystemPrompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CodeReviewServiceFactoryTest {
    @Test
    void passesSettingsAndPreservesDefaultsForLegacyCallers() {
        AiClientFactory clients = mock(AiClientFactory.class);
        AiClient client = mock(AiClient.class);
        when(clients.getClient(any())).thenReturn(client);
        CodeReviewServiceFactory factory = new CodeReviewServiceFactory(clients, mock(SessionService.class),
                new ReviewConfigProperties(), mock(ReviewRunLeaseService.class), mock(ReviewStateService.class),
                mock(PriorFindingsService.class), mock(FindingGroundingService.class), mock(ThreadActionExecutor.class),
                mock(PrAuditEventService.class), mock(ReviewSkipService.class), Duration.ofMinutes(10));
        Bot bot = new Bot();
        bot.setUsername("bot");
        SystemPrompt prompt = new SystemPrompt();
        prompt.setId(1L);
        prompt.setReviewSystemPrompt("Review code");
        bot.setSystemPrompt(prompt);
        RepositoryApiClient repository = mock(RepositoryApiClient.class);
        DoubleCheckSettings settings = new DoubleCheckSettings(false, DoubleCheckSettings.Severity.HIGH, 42L);
        assertThat(factory.create(bot, repository, 1000, 8, 500, "", settings).doubleCheckSettings()).isEqualTo(settings);
        assertThat(factory.create(bot, repository, 1000, 8, 500, "").doubleCheckSettings()).isEqualTo(DoubleCheckSettings.defaults());
    }
}
