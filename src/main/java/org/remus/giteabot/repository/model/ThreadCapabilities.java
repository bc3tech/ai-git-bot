package org.remus.giteabot.repository.model;

/** Provider-supported actions for review comment threads. */
public record ThreadCapabilities(boolean canReplyInThread, boolean canEditComment, boolean canResolveThread) {

    public static ThreadCapabilities unsupported() {
        return new ThreadCapabilities(false, false, false);
    }
}
