package org.remus.giteabot.agent.loop;

/** Mandatory request context cannot fit the strategy's configured budget policy. */
public final class ContextBudgetExceededException extends RuntimeException {

    public ContextBudgetExceededException(String message) {
        super(message);
    }
}
