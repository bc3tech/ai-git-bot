package org.remus.giteabot.review.state;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.BotRepository;
import org.remus.giteabot.admin.GiteaClientFactory;
import org.remus.giteabot.admin.GitIntegration;
import org.remus.giteabot.repository.RepositoryApiClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewStateGarbageCollectorTest {

    @Mock private ReviewRevisionRepository revisionRepository;
    @Mock private ReviewStateService stateService;
    @Mock private BotRepository botRepository;
    @Mock private GiteaClientFactory clientFactory;
    @Mock private RepositoryApiClient apiClient;

    private ReviewStateGarbageCollector collector;
    private Instant now;
    private ReviewRevisionRecord candidate;
    private Bot bot;

    @BeforeEach
    void setUp() {
        collector = new ReviewStateGarbageCollector(revisionRepository, stateService, botRepository,
                clientFactory, Duration.ofDays(30));
        now = Instant.parse("2026-10-08T12:00:00Z");
        collector.setNowSupplierForTest(() -> now);
        candidate = new ReviewRevisionRecord();
        candidate.setBotId(2L);
        candidate.setRepoOwner("owner");
        candidate.setRepoName("repo");
        candidate.setPrNumber(42L);
        candidate.setWorkflowKey("review");
        candidate.setPrClosedAt(now.minus(Duration.ofDays(31)));
        bot = new Bot();
        bot.setGitIntegration(new GitIntegration());
        lenient().when(revisionRepository.findByPrClosedAtBefore(now.minus(Duration.ofDays(30))))
                .thenReturn(List.of(candidate));
        lenient().when(botRepository.findByIdWithIntegrations(2L)).thenReturn(Optional.of(bot));
        lenient().when(clientFactory.getApiClient(bot.getGitIntegration())).thenReturn(apiClient);
    }

    @Test
    void deletesStateOnlyWhenProviderConfirmsPrClosed() {
        when(apiClient.getPullRequestDetails("owner", "repo", 42L))
                .thenReturn(Map.of("state", "closed", "merged", true));
        when(stateService.deleteClosedPullRequestState(2L, "owner", "repo", 42L, now.minus(Duration.ofDays(30))))
                .thenReturn(4);

        assertThat(collector.collectOnce()).isEqualTo(4);

        verify(stateService).deleteClosedPullRequestState(2L, "owner", "repo", 42L, now.minus(Duration.ofDays(30)));
    }

    @Test
    void openPullRequestIsRetainedAndCloseMarkerIsCleared() {
        when(apiClient.getPullRequestDetails("owner", "repo", 42L))
                .thenReturn(Map.of("state", "open", "merged", false));

        assertThat(collector.collectOnce()).isZero();

        verify(revisionRepository).clearPrClosed(2L, "owner", "repo", 42L);
        verify(stateService, never()).deleteClosedPullRequestState(any(), any(), any(), any(), any());
        verify(revisionRepository, never()).deleteForPullRequest(any(), any(), any(), any());
    }

    @Test
    void providerReadFailureRetainsRows() {
        when(apiClient.getPullRequestDetails("owner", "repo", 42L)).thenThrow(new IllegalStateException("offline"));

        assertThat(collector.collectOnce()).isZero();

        verify(stateService, never()).deleteClosedPullRequestState(any(), any(), any(), any(), any());
        verify(revisionRepository, never()).deleteForPullRequest(any(), any(), any(), any());
        verify(revisionRepository, never()).clearPrClosed(any(), any(), any(), any());
    }

    @ParameterizedTest
    @MethodSource("unknownProviderStates")
    void unknownProviderStateRetainsRowsAndCloseMarker(Map<String, Object> details) {
        when(apiClient.getPullRequestDetails("owner", "repo", 42L)).thenReturn(details);
        assertThat(collector.collectOnce()).isZero();
        verify(stateService, never()).deleteClosedPullRequestState(any(), any(), any(), any(), any());
        verify(revisionRepository, never()).deleteForPullRequest(any(), any(), any(), any());
        verify(revisionRepository, never()).clearPrClosed(any(), any(), any(), any());
    }

    private static Stream<Map<String, Object>> unknownProviderStates() {
        return Stream.of(null, Map.of("state", "unrecognized"));
    }

    @Test
    void noRevisionOlderThanRetentionDoesNoProviderRead() {
        when(revisionRepository.findByPrClosedAtBefore(any())).thenReturn(List.of());

        assertThat(collector.collectOnce()).isZero();

        verify(botRepository, never()).findByIdWithIntegrations(eq(2L));
    }
}
