package org.remus.giteabot.review.state;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.review.state.ReviewRunLeaseService.LeaseToken;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "review-state.lease-ttl=10s",
        "review-state.lease-wait-max=2s"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReviewRunLeaseServiceTest {

    @Autowired private ReviewRunLeaseService leaseService;
    @Autowired private ReviewRunLeaseRepository leaseRepository;

    @BeforeEach
    void clearLeases() {
        leaseRepository.deleteAll();
    }

    @Test
    void simultaneousAcquiresSerializeOnOneFencingToken() throws Exception {
        LeaseToken initialized = leaseService.acquire(1L, "owner", "repo", 3L);
        leaseService.release(initialized);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<LeaseToken> first = CompletableFuture.supplyAsync(() -> acquireTogether(ready, start));
        CompletableFuture<LeaseToken> second = CompletableFuture.supplyAsync(() -> acquireTogether(ready, start));
        assertThat(ready.await(1, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        CompletableFuture.anyOf(first, second).get(5, TimeUnit.SECONDS);
        CompletableFuture<LeaseToken> waiterFuture = first.isDone() ? second : first;
        LeaseToken holder = first.isDone() ? first.get() : second.get();
        assertThat(waiterFuture.isDone()).isFalse();
        leaseService.release(holder);
        LeaseToken waiter = waiterFuture.get(5, TimeUnit.SECONDS);

        assertThat(waiter.leaseToken()).isGreaterThan(holder.leaseToken());
        leaseService.release(waiter);
    }

    private LeaseToken acquireTogether(CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(1, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent acquisition did not start");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        return leaseService.acquire(1L, "owner", "repo", 3L);
    }

    @Test
    void acquisitionTimesOutWhenLeaseRemainsHeld() {
        LeaseToken held = leaseService.acquire(1L, "owner", "repo", 3L);

        assertThatThrownBy(() -> leaseService.acquire(1L, "owner", "repo", 3L))
                .isInstanceOf(ReviewRunLeaseService.LeaseAcquisitionTimeoutException.class);
        leaseService.release(held);
    }

    @Test
    void renewExtendsLeaseAndReleaseInvalidatesToken() {
        LeaseToken token = leaseService.acquire(1L, "owner", "repo", 3L);
        leaseService.renew(token);
        leaseService.assertHeld(token);

        leaseService.release(token);

        assertThatThrownBy(() -> leaseService.assertHeld(token))
                .isInstanceOf(ReviewRunLeaseService.LeaseLostException.class);
    }
}
