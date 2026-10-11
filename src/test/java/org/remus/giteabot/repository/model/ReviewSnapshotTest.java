package org.remus.giteabot.repository.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewSnapshotTest {
    @Test void effectiveBaseUsesDiffOriginNotTargetTip() {
        ReviewSnapshot snapshot = new ReviewSnapshot("head", "target-tip", "diff-origin", "diff", true);
        assertThat(snapshot.effectiveBaseSha()).isEqualTo("diff-origin");
        assertThat(snapshot.pinned()).isTrue();
        assertThat(snapshot.withDiff("filtered").effectiveBaseSha()).isEqualTo("diff-origin");
        assertThat(snapshot.withoutInline().pinned()).isTrue();
    }
    @Test void missingEffectiveBaseNeverFallsBackToTargetTip() {
        assertThat(new ReviewSnapshot("head", "target-tip", null, "diff", true).pinned()).isFalse();
        assertThat(ReviewSnapshot.summaryOnly("diff").pinned()).isFalse();
    }
}
