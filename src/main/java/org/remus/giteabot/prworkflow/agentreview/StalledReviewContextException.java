package org.remus.giteabot.prworkflow.agentreview;

/** Final legacy request retention left a second consecutive turn without usable progress. */
public final class StalledReviewContextException extends RuntimeException {
    public StalledReviewContextException() {
        super("Legacy review stalled without new usable evidence");
    }
}
