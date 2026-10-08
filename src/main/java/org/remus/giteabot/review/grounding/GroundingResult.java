package org.remus.giteabot.review.grounding;

import org.remus.giteabot.review.ReviewDocument;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record GroundingResult(List<ReviewDocument.Finding> publish, List<String> summaryNotes,
                              List<ThreadAction> threadActions, List<FindingDisposition> dispositions,
                              Map<String, FindingEvidence> evidence, Map<String, List<UUID>> supersedes,
                              boolean degraded) {
    public GroundingResult {
        publish = List.copyOf(publish);
        summaryNotes = List.copyOf(summaryNotes);
        threadActions = List.copyOf(threadActions);
        dispositions = List.copyOf(dispositions);
        evidence = Map.copyOf(evidence);
        supersedes = Map.copyOf(supersedes);
    }

    public record FindingEvidence(String quoteHash, String contextHash, String storedQuote) {}
}
