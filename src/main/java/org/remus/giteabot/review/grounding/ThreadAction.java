package org.remus.giteabot.review.grounding;

public sealed interface ThreadAction {
    PriorFindingsDigest.PriorFinding prior();
    String reason();

    record UpheldReply(PriorFindingsDigest.PriorFinding prior, String reason) implements ThreadAction {}
    record Withdraw(PriorFindingsDigest.PriorFinding prior, String reason) implements ThreadAction {}
}
