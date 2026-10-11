package org.remus.giteabot.prworkflow.agentreview;

import org.remus.giteabot.review.state.ReviewRunLeaseService;
import org.remus.giteabot.review.state.ReviewRunLeaseService.LeaseToken;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;

final class AgentReviewLeaseHeartbeat implements AutoCloseable {
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("agent-review-lease-heartbeat").factory());
    private final AtomicReference<RuntimeException> failure = new AtomicReference<>();

    AgentReviewLeaseHeartbeat(ReviewRunLeaseService leases, LeaseToken lease, Duration ttl) {
        long interval = Math.max(1, ttl.toMillis() / 3);
        scheduler.scheduleAtFixedRate(() -> {
            if (failure.get() != null) return;
            try {
                leases.renew(lease);
            } catch (RuntimeException e) {
                ReviewRunLeaseService.LeaseLostException lost = e instanceof ReviewRunLeaseService.LeaseLostException l
                        ? l : new ReviewRunLeaseService.LeaseLostException(lease);
                if (lost != e) lost.initCause(e);
                failure.compareAndSet(null, lost);
            }
        }, interval, interval, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    void assertHealthy() {
        RuntimeException error = failure.get();
        if (error != null) throw error;
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        scheduler.close();
    }
}
