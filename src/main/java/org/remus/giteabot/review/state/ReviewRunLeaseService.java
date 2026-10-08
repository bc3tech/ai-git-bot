package org.remus.giteabot.review.state;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class ReviewRunLeaseService {

    private final ReviewRunLeaseRepository leaseRepository;
    private final Duration leaseTtl;
    private final Duration leaseWaitMax;
    private final TransactionTemplate insertTransaction;

    public ReviewRunLeaseService(ReviewRunLeaseRepository leaseRepository,
                                 PlatformTransactionManager transactionManager,
                                 @Value("${review-state.lease-ttl:10m}") Duration leaseTtl,
                                 @Value("${review-state.lease-wait-max:30m}") Duration leaseWaitMax) {
        this.leaseRepository = leaseRepository;
        this.leaseTtl = leaseTtl;
        this.leaseWaitMax = leaseWaitMax;
        this.insertTransaction = new TransactionTemplate(transactionManager);
        this.insertTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Waits for and acquires the single lease shared by all review workflows for this PR. */
    public LeaseToken acquire(Long botId, String owner, String repo, Long prNumber) {
        validateKey(botId, owner, repo, prNumber);
        ensureLeaseRow(botId, owner, repo, prNumber);

        String leaseOwner = UUID.randomUUID().toString();
        long deadline = System.nanoTime() + leaseWaitMax.toNanos();
        long backoffMillis = 100;
        while (true) {
            Instant now = Instant.now();
            int acquired = leaseRepository.acquireIfExpired(botId, owner, repo, prNumber,
                    leaseOwner, now, now.plus(leaseTtl));
            if (acquired == 1) {
                ReviewRunLease lease = leaseRepository.findByBotIdAndRepoOwnerAndRepoNameAndPrNumber(
                        botId, owner, repo, prNumber).orElseThrow();
                return new LeaseToken(botId, owner, repo, prNumber, leaseOwner, lease.getLeaseToken());
            }
            if (System.nanoTime() >= deadline) {
                throw new LeaseAcquisitionTimeoutException(botId, owner, repo, prNumber, leaseWaitMax);
            }
            try {
                Thread.sleep(Math.min(backoffMillis, Math.max(1, (deadline - System.nanoTime()) / 1_000_000)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LeaseAcquisitionInterruptedException(e);
            }
            backoffMillis = Math.min(backoffMillis * 2, 1_000);
        }
    }

    /** Extends an unexpired lease; an expired or fenced-out lease cannot be revived. */
    public void renew(LeaseToken token) {
        Instant now = Instant.now();
        int renewed = leaseRepository.renewIfHeld(token.botId(), token.owner(), token.repo(), token.prNumber(),
                token.leaseOwner(), token.leaseToken(), now, now.plus(leaseTtl));
        if (renewed != 1) {
            throw new LeaseLostException(token);
        }
    }

    /** Throws when this token is stale, released, or expired. */
    public void assertHeld(LeaseToken token) {
        boolean held = leaseRepository.existsByBotIdAndRepoOwnerAndRepoNameAndPrNumberAndLeaseOwnerAndLeaseTokenAndLeaseExpiresAtAfter(
                token.botId(), token.owner(), token.repo(), token.prNumber(), token.leaseOwner(),
                token.leaseToken(), Instant.now());
        if (!held) {
            throw new LeaseLostException(token);
        }
    }

    /** Releases only the matching fencing token; stale callers cannot release a newer lease. */
    public void release(LeaseToken token) {
        leaseRepository.releaseIfHeld(token.botId(), token.owner(), token.repo(), token.prNumber(),
                token.leaseOwner(), token.leaseToken());
    }

    private void ensureLeaseRow(Long botId, String owner, String repo, Long prNumber) {
        if (leaseRepository.existsByBotIdAndRepoOwnerAndRepoNameAndPrNumber(botId, owner, repo, prNumber)) {
            return;
        }
        try {
            insertTransaction.executeWithoutResult(status -> {
                ReviewRunLease row = new ReviewRunLease();
                row.setBotId(botId);
                row.setRepoOwner(owner);
                row.setRepoName(repo);
                row.setPrNumber(prNumber);
                row.setLeaseToken(0);
                leaseRepository.saveAndFlush(row);
            });
        } catch (DataIntegrityViolationException ignored) {
            // Another process initialized the unique PR row first.
        }
    }

    private static void validateKey(Long botId, String owner, String repo, Long prNumber) {
        if (botId == null || owner == null || owner.isBlank() || repo == null || repo.isBlank() || prNumber == null) {
            throw new IllegalArgumentException("A bot, repository owner/name, and PR number are required");
        }
    }

    public record LeaseToken(Long botId, String owner, String repo, Long prNumber,
                             String leaseOwner, long leaseToken) {}

    public static final class LeaseAcquisitionTimeoutException extends RuntimeException {
        public LeaseAcquisitionTimeoutException(Long botId, String owner, String repo, Long prNumber,
                                                Duration waitMax) {
            super("Timed out after " + waitMax + " waiting for review lease on "
                    + botId + ":" + owner + "/" + repo + "#" + prNumber);
        }
    }

    public static final class LeaseAcquisitionInterruptedException extends RuntimeException {
        public LeaseAcquisitionInterruptedException(InterruptedException cause) {
            super("Interrupted while waiting for review lease", cause);
        }
    }

    public static final class LeaseLostException extends RuntimeException {
        public LeaseLostException(LeaseToken token) {
            super("Review lease lost for " + token.botId() + ":" + token.owner() + "/"
                    + token.repo() + "#" + token.prNumber() + " (token " + token.leaseToken() + ")");
        }
    }
}
