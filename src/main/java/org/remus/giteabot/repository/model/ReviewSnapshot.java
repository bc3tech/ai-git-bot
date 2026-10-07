package org.remus.giteabot.repository.model;

/**
 * The exact pull request revision a generated review is based on.
 *
 * @param headSha         head commit the diff was produced from; {@code null} when unknown
 * @param baseSha         base (or merge-base) commit of the diff; {@code null} when unknown
 * @param startSha        provider-specific start commit (GitLab diff versions); otherwise {@code null}
 * @param diff            unified diff of exactly this revision
 * @param inlineSupported whether the provider can publish comments bound to this revision
 */
public record ReviewSnapshot(String headSha, String baseSha, String startSha, String diff,
                             boolean inlineSupported) {

    public ReviewSnapshot {
        diff = diff == null ? "" : diff;
        if (inlineSupported && (headSha == null || headSha.isBlank())) {
            throw new IllegalArgumentException("Inline-capable snapshots require a head commit");
        }
    }

    /** A snapshot that can only be published as a summary (no revision-bound inline support). */
    public static ReviewSnapshot summaryOnly(String diff) {
        return new ReviewSnapshot(null, null, null, diff, false);
    }

    /** Returns a copy whose diff is replaced (for example after excluding filtered files). */
    public ReviewSnapshot withDiff(String filteredDiff) {
        return new ReviewSnapshot(headSha, baseSha, startSha, filteredDiff, inlineSupported);
    }

    /** Returns a copy that is no longer eligible for inline publication. */
    public ReviewSnapshot withoutInline() {
        return new ReviewSnapshot(headSha, baseSha, startSha, diff, false);
    }
}
