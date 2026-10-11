package org.remus.giteabot.prworkflow.agentreview;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.agent.model.ImplementationPlan;
import org.remus.giteabot.agent.validation.ToolResult;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyReviewEvidenceTest {
    private LegacyReviewEvidence.Key cat(String ref, String... args) {
        return LegacyReviewEvidence.Key.tool(ref, ImplementationPlan.ToolRequest.builder()
                .id("ignored").tool("cat").args(List.of(args)).build());
    }

    @Test
    void narrowReadsCannotClearFullAcquisitionWithoutVerifiedTotalCoverage() {
        var ledger = new LegacyReviewEvidence();
        var full = LegacyReviewEvidence.Key.file("sha", "A");
        ledger.requested(full);
        ledger.acquired(full, new ReviewAgentStrategy.FileEvidence(true, false, "one\ntwo\n", 1, null));
        ledger.select(2_000, ignored -> true);
        var range = cat("sha", "A", "3", "4");
        ledger.acquired(range, LegacyReviewEvidence.toolEvidence(range,
                new ToolResult(true, 0, "3 | three\n4 | four\n", "")));
        ledger.select(2_000, ignored -> true);
        assertThat(ledger.coverageNotes()).isNotEmpty();
        var toEof = cat("sha", "A", "3");
        ledger.acquired(toEof, LegacyReviewEvidence.toolEvidence(toEof,
                new ToolResult(true, 0, "3 | three\n4 | four\n", "")));
        ledger.select(2_000, ignored -> true);
        assertThat(ledger.coverageNotes()).isEmpty();
    }

    @Test
    void verifiedCoverageOnlyCombinesSameSourceAndImmutableRevision() {
        var ledger = new LegacyReviewEvidence();
        var full = LegacyReviewEvidence.Key.file("sha", "A");
        ledger.acquired(full, new ReviewAgentStrategy.FileEvidence(true, false, "one\ntwo\n", 1, 4));
        var otherRef = cat("other-sha", "A", "3");
        ledger.acquired(otherRef, new ReviewAgentStrategy.FileEvidence(true, false, "three\nfour\n", 3, 4));
        var otherSource = cat("sha", "B", "3");
        ledger.acquired(otherSource, new ReviewAgentStrategy.FileEvidence(true, false, "three\nfour\n", 3, 4));
        ledger.select(4_000, ignored -> true);
        assertThat(ledger.coverageNotes()).isNotEmpty();
        var same = cat("sha", "A", "3");
        ledger.acquired(same, new ReviewAgentStrategy.FileEvidence(true, false, "three\nfour\n", 3, 4));
        ledger.select(4_000, ignored -> true);
        assertThat(ledger.coverageNotes()).isEmpty();
    }

    @Test
    void acquisitionLimitationsSurviveEvictionButAcquiredCompleteSourcesDoNotBecomeLimited() {
        var ledger = new LegacyReviewEvidence();
        var partial = LegacyReviewEvidence.Key.file("sha", "A");
        ledger.acquired(partial, new ReviewAgentStrategy.FileEvidence(true, false, "partial", 1, null));
        var complete = LegacyReviewEvidence.Key.file("sha", "B");
        ledger.acquired(complete, ReviewAgentStrategy.FileEvidence.complete("complete"));
        ledger.select(2_000, ignored -> true);
        ledger.commitDelivery();
        ledger.select(0, ignored -> true);
        assertThat(ledger.snapshot()).isEmpty();
        assertThat(ledger.available(complete)).isFalse();
        assertThat(ledger.coverageNotes().getFirst()).contains("1 requested read(s)");
        ledger.acquired(partial, ReviewAgentStrategy.FileEvidence.complete("restored full"));
        ledger.select(2_000, ignored -> true);
        ledger.commitDelivery();
        ledger.select(0, ignored -> true);
        assertThat(ledger.coverageNotes()).isEmpty();
    }

    @Test
    void partialNumberedLineAtToolCharacterCapIsNotVerifiedCoverage() {
        var key = cat("sha", "A");
        var evidence = LegacyReviewEvidence.toolEvidence(key,
                new ToolResult(true, 0, "1 | intact\n2 | cut off\n... (output truncated)", ""));
        assertThat(evidence.success()).isTrue();
        assertThat(evidence.complete()).isFalse();
        assertThat(evidence.content()).isEqualTo("intact\n");
        assertThat(evidence.totalLines()).isNull();
        var failed = LegacyReviewEvidence.toolEvidence(key,
                new ToolResult(false, 1, "1 | error that looks numbered\n", "denied"));
        assertThat(failed.success()).isFalse();
        assertThat(failed.content()).isEmpty();
    }

    @Test
    void unverifiedLastLineFragmentCannotCombineWithNarrowReadsToPretendFullCoverage() {
        var ledger = new LegacyReviewEvidence();
        var full = LegacyReviewEvidence.Key.file("sha", "A");
        ledger.acquired(full, new ReviewAgentStrategy.FileEvidence(true, false, "first\npart", 1, 3));
        ledger.select(2_000, ignored -> true);
        assertThat(ledger.snapshot()).contains("last line partial", "remainder of line 2");
        var third = cat("sha", "A", "3");
        ledger.acquired(third, new ReviewAgentStrategy.FileEvidence(true, false, "third\n", 3, 3));
        ledger.select(2_000, ignored -> true);
        assertThat(ledger.coverageNotes()).isNotEmpty();
        var second = cat("sha", "A", "2", "2");
        ledger.acquired(second, new ReviewAgentStrategy.FileEvidence(true, false, "second\n", 2, 3));
        ledger.select(2_000, ignored -> true);
        assertThat(ledger.coverageNotes()).isEmpty();
    }

    @Test
    void finalRetainedAvailabilityControlsProgressAndAggressiveEvictionDoesNotFabricateIt() {
        var ledger = new LegacyReviewEvidence();
        var key = LegacyReviewEvidence.Key.file("sha", "A");
        ledger.acquired(key, ReviewAgentStrategy.FileEvidence.complete("retained content\n".repeat(100)));
        ledger.select(900, ignored -> true);
        var first = ledger.availability();
        assertThat(first).hasSize(1);
        ledger.select(900, ignored -> true);
        assertThat(ledger.delivered(Set.of(key), first)).isFalse();
        ledger.select(0, ignored -> true);
        assertThat(ledger.delivered(Set.of(key), first)).isFalse();
        assertThat(ledger.available(key)).isFalse();
        var evicted = ledger.availability();
        ledger.acquired(key, ReviewAgentStrategy.FileEvidence.complete("retained content\n".repeat(100)));
        ledger.select(900, ignored -> true);
        assertThat(ledger.delivered(Set.of(key), evicted)).isTrue();
        assertThat(ledger.snapshot()).contains("source=requestFiles A", "ref=sha", "Omitted:");
    }

    @Test
    void fullFetchedSourceIsNotCompleteDeliveredCoverageWhenFinalRequestOnlyContainsExcerpt() {
        var ledger = new LegacyReviewEvidence();
        var full = LegacyReviewEvidence.Key.file("sha", "large");
        ledger.acquired(full, ReviewAgentStrategy.FileEvidence.complete("large source line\n".repeat(10_000)));
        assertThat(ledger.coverageNotes()).isNotEmpty();
        ledger.select(900, ignored -> true);
        assertThat(ledger.snapshot()).contains("Omitted:");
        assertThat(ledger.retainedSourceCharacters()).isLessThan(900);
        String first = ledger.snapshot();
        ledger.select(900, ignored -> true);
        assertThat(ledger.snapshot()).isEqualTo(first);
        assertThat(ledger.coverageNotes()).isNotEmpty();
        ledger.commitDelivery();
        ledger.select(0, ignored -> true);
        assertThat(ledger.coverageNotes()).isNotEmpty();
        assertThat(ledger.retainedSourceCharacters()).isZero();
    }

    @Test
    void retryReselectionInvalidatesProvisionalCompleteCoverageBeforeItWasActuallyDelivered() {
        var ledger = new LegacyReviewEvidence();
        var full = LegacyReviewEvidence.Key.file("sha", "A");
        ledger.acquired(full, ReviewAgentStrategy.FileEvidence.complete("whole source\n".repeat(50)));
        ledger.select(2_000, ignored -> true);
        assertThat(ledger.coverageNotes()).isEmpty();
        ledger.select(450, ignored -> true);
        assertThat(ledger.coverageNotes()).isNotEmpty();
        ledger.commitDelivery();
        ledger.select(0, ignored -> true);
        assertThat(ledger.coverageNotes()).isNotEmpty();
    }

    @Test
    void fetchedButEvictedInTheSameFinalSelectionNeverCountsAsDeliveredCompleteCoverage() {
        var ledger = new LegacyReviewEvidence();
        var a = LegacyReviewEvidence.Key.file("sha", "A");
        var b = LegacyReviewEvidence.Key.file("sha", "B");
        ledger.acquired(a, ReviewAgentStrategy.FileEvidence.complete("A".repeat(600)));
        ledger.select(1_000, ignored -> true);
        ledger.acquired(b, ReviewAgentStrategy.FileEvidence.complete("B".repeat(600)));
        ledger.select(1_000, ignored -> true);
        assertThat(ledger.available(a)).isFalse();
        assertThat(ledger.available(b)).isTrue();
        assertThat(ledger.coverageNotes().getFirst()).contains("1 requested read(s)");
        ledger.commitDelivery();
        ledger.select(0, ignored -> true);
        assertThat(ledger.coverageNotes().getFirst()).contains("1 requested read(s)");
    }

    @Test
    void inventoryRetainsBoundedEvictedAndUndeliveredIdentitiesWithoutAcknowledgingThemAsAvailable() {
        var ledger = new LegacyReviewEvidence();
        var a = LegacyReviewEvidence.Key.file("sha", "A");
        ledger.acquired(a, ReviewAgentStrategy.FileEvidence.complete("delivered"));
        ledger.select(2_000, ignored -> true);
        ledger.commitDelivery();
        ledger.select(0, ignored -> true);
        var b = LegacyReviewEvidence.Key.file("sha", "B");
        ledger.acquired(b, ReviewAgentStrategy.FileEvidence.failed());
        assertThat(ledger.inventory()).contains("E1 delivered-evicted requestFiles A ref=sha",
                "E2 requested-undelivered requestFiles B ref=sha");
        assertThat(ledger.snapshot()).isEmpty();
        for (int i = 0; i < 100; i++) ledger.requested(LegacyReviewEvidence.Key.file("sha", "file-" + i));
        assertThat(ledger.inventory().length()).isLessThan(1_150);
        assertThat(ledger.inventory()).contains("additional entries omitted");
    }

    @Test
    void sourceLineBoundariesMatchFilesReadAllLinesNotUnicodeLineSeparators() {
        String source = "form\ffeed\u2028separator\r\nsecond\nthird\rfourth";
        var full = ReviewAgentStrategy.FileEvidence.complete(source);
        assertThat(full.totalLines()).isEqualTo(4);
        var ledger = new LegacyReviewEvidence();
        var key = LegacyReviewEvidence.Key.file("sha", "A");
        ledger.acquired(key, full);
        ledger.select(2_000, ignored -> true);
        assertThat(ledger.snapshot()).contains("1 | form\ffeed\u2028separator\n", "4 | fourth\n");
        assertThat(ledger.coverageNotes()).isEmpty();
    }

    @Test
    void numberedCatLinesPreserveUnicodeSeparatorsWithinValidSourceLines() {
        var key = cat("sha", "A");
        String line = "before\u0085middle\u2028next\u2029after";
        var evidence = LegacyReviewEvidence.toolEvidence(key,
                new ToolResult(true, 0, "1 | " + line + "\n2 | last\n", ""));
        assertThat(evidence.success()).isTrue();
        assertThat(evidence.complete()).isTrue();
        assertThat(evidence.totalLines()).isEqualTo(2);
        assertThat(evidence.content()).isEqualTo(line + "\nlast\n");
        var ledger = new LegacyReviewEvidence();
        ledger.acquired(key, evidence);
        ledger.select(2_000, ignored -> true);
        assertThat(ledger.snapshot()).contains("1 | " + line + "\n2 | last\n");
        assertThat(ledger.coverageNotes()).isEmpty();
    }

    @Test
    void catEmptySentinelAndShortBoundedReadProvideOnlyVerifiedCoverage() {
        var full = cat("sha", "empty");
        var empty = LegacyReviewEvidence.toolEvidence(full,
                new ToolResult(true, 0, "No content in requested line range.", ""));
        assertThat(empty.complete()).isTrue();
        assertThat(empty.content()).isEmpty();
        var range = cat("sha", "empty", "5", "20");
        var emptyRange = LegacyReviewEvidence.toolEvidence(range,
                new ToolResult(true, 0, "No content in requested line range.", ""));
        assertThat(emptyRange.complete()).isFalse();
        assertThat(emptyRange.totalLines()).isNull();
        var bounded = cat("sha", "A", "1", "100");
        StringBuilder numbered = new StringBuilder();
        for (int i = 1; i <= 50; i++) numbered.append(i).append(" | line\n");
        var read = LegacyReviewEvidence.toolEvidence(bounded,
                new ToolResult(true, 0, numbered.toString(), ""));
        assertThat(read.totalLines()).isEqualTo(50);
        var ledger = new LegacyReviewEvidence();
        ledger.requested(LegacyReviewEvidence.Key.file("sha", "A"));
        ledger.acquired(bounded, read);
        ledger.select(4_000, ignored -> true);
        assertThat(ledger.coverageNotes()).isEmpty();
    }
}
