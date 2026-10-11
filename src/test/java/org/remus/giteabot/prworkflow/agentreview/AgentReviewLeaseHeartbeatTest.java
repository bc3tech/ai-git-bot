package org.remus.giteabot.prworkflow.agentreview;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.review.state.ReviewRunLeaseService;
import org.remus.giteabot.review.state.ReviewRunLeaseService.LeaseToken;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentReviewLeaseHeartbeatTest {
    private final ReviewRunLeaseService leases = mock(ReviewRunLeaseService.class);
    private final LeaseToken lease = new LeaseToken(1L, "owner", "repo", 1L, "holder", 1L);

    @Test
    void renewsWhileRunIsBlockedAndStopsOnClose() throws Exception {
        CountDownLatch renewed = new CountDownLatch(2);
        doAnswer(inv -> { renewed.countDown(); return null; }).when(leases).renew(lease);
        try (var heartbeat = new AgentReviewLeaseHeartbeat(leases, lease, Duration.ofMillis(90))) {
            assertThat(renewed.await(3, TimeUnit.SECONDS)).isTrue();
            heartbeat.assertHealthy();
        }
        clearInvocations(leases);
        Thread.sleep(120);
        verifyNoInteractions(leases);
    }

    @Test
    void failedRenewalFencesTheRun() throws Exception {
        CountDownLatch attempted = new CountDownLatch(1);
        var lost = new ReviewRunLeaseService.LeaseLostException(lease);
        doAnswer(inv -> { attempted.countDown(); throw lost; }).when(leases).renew(lease);
        try (var heartbeat = new AgentReviewLeaseHeartbeat(leases, lease, Duration.ofMillis(90))) {
            assertThat(attempted.await(3, TimeUnit.SECONDS)).isTrue();
            // Wait for the callback to publish its failure after signalling the latch.
            assertThatThrownBy(() -> {
                for (int i = 0; i < 100; i++) {
                    heartbeat.assertHealthy();
                    Thread.sleep(10);
                }
            }).isSameAs(lost);
        }
    }
}
