package org.remus.giteabot.repository;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.repository.model.ReviewSnapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewSnapshotTest {

    @Test
    void inlineSnapshotsRequireAHeadCommit() {
        assertThatThrownBy(() -> new ReviewSnapshot(null, "base", null, "diff", true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new ReviewSnapshot("head", "base", null, "diff", true).inlineSupported()).isTrue();
    }

    @Test
    void summaryOnlyAndCopiesPreserveIdentity() {
        ReviewSnapshot snapshot = new ReviewSnapshot("h", "b", "s", "raw", true);
        assertThat(snapshot.withDiff("filtered")).isEqualTo(new ReviewSnapshot("h", "b", "s", "filtered", true));
        assertThat(snapshot.withoutInline().inlineSupported()).isFalse();
        assertThat(ReviewSnapshot.summaryOnly(null).diff()).isEmpty();
        assertThat(ReviewSnapshot.summaryOnly("d").inlineSupported()).isFalse();
    }
}
