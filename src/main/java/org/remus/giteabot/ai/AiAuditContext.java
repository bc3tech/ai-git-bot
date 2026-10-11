package org.remus.giteabot.ai;

/**
 * Thread-local context carrying the logical session identifier (e.g.
 * {@code owner/repo#42}) and, inside an agent loop, the current round of the
 * work being processed, so that AI usage and error records can be correlated
 * with the originating session (and the round that produced them).
 *
 * <p>Callers that orchestrate AI interactions (webhook handlers, PR workflows)
 * set the session id at the start of processing and must clear it in a
 * {@code finally} block.</p>
 */
public final class AiAuditContext {

    private static final ThreadLocal<String> SESSION_ID = new ThreadLocal<>();
    private static final ThreadLocal<Integer> ROUND = new ThreadLocal<>();

    private AiAuditContext() {
    }

    public static void setSessionId(String sessionId) {
        SESSION_ID.set(sessionId);
    }

    public static String getSessionId() {
        return SESSION_ID.get();
    }

    /** Marks the agent-loop round the current AI call belongs to. */
    public static void setRound(int round) {
        ROUND.set(round);
    }

    /** The agent-loop round of the current AI call, or {@code null} outside a loop. */
    public static Integer getRound() {
        return ROUND.get();
    }

    /** Clears only the round marker, leaving the session id in place. */
    public static void clearRound() {
        ROUND.remove();
    }

    public static void clear() {
        SESSION_ID.remove();
        ROUND.remove();
    }
}
