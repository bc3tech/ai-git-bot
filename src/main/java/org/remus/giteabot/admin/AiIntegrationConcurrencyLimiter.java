package org.remus.giteabot.admin;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.prworkflow.WorkflowCancelledException;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * JVM-local, per-{@link AiIntegration} concurrency control for AI job execution.
 *
 * <p>Every AI integration carries a {@code parallelWorkerLimit}: {@code 0} (the
 * default) means unlimited parallel execution — the historical behaviour — while
 * {@code 1..20} caps how many jobs of that integration may run at the same time.
 * {@link #withPermit} blocks the calling thread until a slot is free, so excess
 * jobs queue instead of being dropped or failing. Different integrations use
 * different permit pools and never block each other.</p>
 *
 * <p>Because the affected work is dispatched on virtual threads (the
 * {@code @Async} webhook entrypoints in {@code BotWebhookService}), waiting on a
 * slot costs one cheap thread, not a platform-thread-pool slot.</p>
 *
 * <h2>Scope and limitations</h2>
 * <ul>
 *     <li><strong>Single-instance only.</strong> Like
 *     {@code PrWorkflowRunLockManager} the permits live in this JVM, so a
 *     multi-instance deployment applies the limit per instance.</li>
 *     <li><strong>The capacity is adjusted in place, never rebuilt.</strong> One
 *     gate per integration lives for the life of the process, so the in-flight
 *     count survives a limit change: lowering {@code 2 -> 1} while two jobs run
 *     makes the next job wait until one of them finishes instead of starting a
 *     third, and a running job is never displaced.</li>
 *     <li>The limit is read from the {@link AiIntegration} instance the caller
 *     passes in, which is the entity the job was dispatched with. Reading it
 *     through the entity keeps the hot path free of a database round-trip; a
 *     caller holding a stale copy therefore applies the value it knows for the
 *     duration of that call.</li>
 *     <li>Gate entries stay until {@link #forget(Long)} is called for a deleted
 *     integration, so the map is bounded by the number of integrations.</li>
 * </ul>
 */
@Slf4j
@Component
public class AiIntegrationConcurrencyLimiter {

    private final ConcurrentMap<Long, Gate> gates = new ConcurrentHashMap<>();

    /**
     * Runs {@code action} while holding one concurrency slot for
     * {@code integration}, returning its result. Blocks until a slot becomes
     * available when the integration's limit is reached (queuing the job); runs
     * immediately when the limit is {@code 0} / negative or the integration is
     * unknown / unsaved.
     *
     * @throws WorkflowCancelledException when the wait is interrupted: a job
     *         whose application is shutting down must neither run without a
     *         permit nor be recorded as a failed run
     */
    public <T> T withPermit(AiIntegration integration, Supplier<T> action) {
        Gate gate = gateFor(integration);
        if (gate == null) {
            return action.get();
        }
        gate.acquire();
        try {
            return action.get();
        } finally {
            gate.release();
        }
    }

    /** {@link #withPermit(AiIntegration, Supplier)} for an action with no result. */
    public void runWithPermit(AiIntegration integration, Runnable action) {
        withPermit(integration, () -> {
            action.run();
            return null;
        });
    }

    /** Drops the permit pool of an integration that no longer exists. */
    public void forget(Long integrationId) {
        if (integrationId != null) {
            gates.remove(integrationId);
        }
    }

    /**
     * The permit pool for {@code integration}, or {@code null} when the caller
     * has no persisted integration to key it by. The pool outlives limit
     * changes — only its capacity is updated.
     */
    private Gate gateFor(AiIntegration integration) {
        if (integration == null || integration.getId() == null) {
            return null;
        }
        Gate gate = gates.computeIfAbsent(integration.getId(), id -> new Gate());
        gate.applyLimit(integration.getParallelWorkerLimit());
        return gate;
    }

    /**
     * One integration's permit pool. A lock/condition pair rather than a
     * {@link java.util.concurrent.Semaphore}, because the capacity has to be
     * adjustable without handing out a fresh, fully permitted pool while older
     * jobs are still holding theirs.
     */
    private static final class Gate {

        private final ReentrantLock lock = new ReentrantLock(true);
        private final Condition slotReleased = lock.newCondition();
        /** Guarded by {@link #lock}; {@code <= 0} means unlimited. */
        private int limit;
        /** Guarded by {@link #lock}; jobs currently holding a slot. */
        private int inFlight;

        void applyLimit(int newLimit) {
            lock.lock();
            try {
                if (limit != newLimit) {
                    limit = newLimit;
                    // A raised (or lifted) cap may admit waiters right away.
                    slotReleased.signalAll();
                }
            } finally {
                lock.unlock();
            }
        }

        void acquire() {
            lock.lock();
            try {
                while (limit > 0 && inFlight >= limit) {
                    slotReleased.await();
                }
                inFlight++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new WorkflowCancelledException(
                        "Interrupted while waiting for a free AI integration concurrency slot");
            } finally {
                lock.unlock();
            }
        }

        void release() {
            lock.lock();
            try {
                inFlight--;
                slotReleased.signal();
            } finally {
                lock.unlock();
            }
        }
    }
}
