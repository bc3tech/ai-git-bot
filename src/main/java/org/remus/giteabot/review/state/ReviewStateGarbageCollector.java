package org.remus.giteabot.review.state;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.BotRepository;
import org.remus.giteabot.admin.GiteaClientFactory;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

@Slf4j
@Component
public class ReviewStateGarbageCollector {

    private final ReviewRevisionRepository revisionRepository;
    private final ReviewStateService stateService;
    private final BotRepository botRepository;
    private final GiteaClientFactory clientFactory;
    private final Duration retention;
    private Supplier<Instant> nowSupplier = Instant::now;

    public ReviewStateGarbageCollector(ReviewRevisionRepository revisionRepository,
                                       ReviewStateService stateService,
                                       BotRepository botRepository,
                                       GiteaClientFactory clientFactory,
                                       @Value("${review-state.retention-after-close:P30D}") Duration retention) {
        this.revisionRepository = revisionRepository;
        this.stateService = stateService;
        this.botRepository = botRepository;
        this.clientFactory = clientFactory;
        this.retention = retention;
    }

    void setNowSupplierForTest(Supplier<Instant> nowSupplier) {
        this.nowSupplier = nowSupplier;
    }

    @Scheduled(cron = "${review-state.gc-cron:0 53 4 * * *}")
    public void runGarbageCollection() {
        int deleted = collectOnce();
        if (deleted > 0) {
            log.info("ReviewStateGarbageCollector: deleted {} review-state row(s), retention={}", deleted, retention);
        }
    }

    int collectOnce() {
        Instant cutoff = nowSupplier.get().minus(retention);
        Set<PullRequestKey> processed = new HashSet<>();
        int deleted = 0;
        for (ReviewRevisionRecord revision : revisionRepository.findByPrClosedAtBefore(cutoff)) {
            PullRequestKey key = new PullRequestKey(revision.getBotId(), revision.getRepoOwner(),
                    revision.getRepoName(), revision.getPrNumber());
            if (!processed.add(key)) {
                continue;
            }
            RepositoryApiClient client = providerClient(key);
            if (client == null) {
                continue;
            }
            Map<String, Object> details;
            try {
                details = client.getPullRequestDetails(key.owner(), key.repo(), key.prNumber());
            } catch (RuntimeException e) {
                log.warn("Skipping review-state collection for {}/{}#{}: provider read failed: {}",
                        key.owner(), key.repo(), key.prNumber(), e.getMessage());
                continue;
            }
            PullRequestState state = stateOf(details);
            if (state == PullRequestState.OPEN) {
                revisionRepository.clearPrClosed(key.botId(), key.owner(), key.repo(), key.prNumber());
            } else if (state == PullRequestState.CLOSED) {
                deleted += stateService.deleteClosedPullRequestState(key.botId(), key.owner(), key.repo(), key.prNumber(), cutoff);
            }
        }
        return deleted;
    }

    private RepositoryApiClient providerClient(PullRequestKey key) {
        try {
            Bot bot = botRepository.findByIdWithIntegrations(key.botId()).orElse(null);
            if (bot == null || bot.getGitIntegration() == null) {
                return null;
            }
            return clientFactory.getApiClient(bot.getGitIntegration());
        } catch (RuntimeException e) {
            log.warn("Skipping review-state collection for {}/{}#{}: provider client unavailable: {}",
                    key.owner(), key.repo(), key.prNumber(), e.getMessage());
            return null;
        }
    }

    private static PullRequestState stateOf(Map<String, Object> details) {
        if (details == null || details.isEmpty()) {
            return PullRequestState.UNKNOWN;
        }
        Object rawState = details.get("state");
        String state = rawState instanceof String value ? value.toLowerCase(Locale.ROOT) : null;
        if ("closed".equals(state) || Boolean.TRUE.equals(details.get("merged"))) {
            return PullRequestState.CLOSED;
        }
        if ("open".equals(state)) {
            return PullRequestState.OPEN;
        }
        return PullRequestState.UNKNOWN;
    }

    private enum PullRequestState { OPEN, CLOSED, UNKNOWN }

    private record PullRequestKey(Long botId, String owner, String repo, Long prNumber) {}
}
