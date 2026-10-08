package org.remus.giteabot.review;

import org.remus.giteabot.repository.model.DiffSide;

import java.util.List;
import java.util.Objects;

/**
 * Provider-neutral normalized review: a concise summary plus individual findings.
 *
 * @param summary    overall assessment (Markdown); never repeats located finding detail
 * @param findings   individual findings, located or review-wide
 * @param structured {@code true} when parsed from the structured envelope, {@code false} for
 *                   legacy/fallback text where {@code summary} carries the whole review
 */
public record ReviewDocument(String summary, List<Finding> findings, boolean structured) {

    public ReviewDocument {
        summary = summary == null ? "" : summary.strip();
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    /** A legacy/unstructured review; the full text becomes the summary. */
    public static ReviewDocument text(String text) {
        return new ReviewDocument(text, List.of(), false);
    }

    public boolean isEmpty() {
        return summary.isBlank() && findings.isEmpty();
    }

    /**
     * One review finding as requested by the model. A location is only a request: it is
     * validated against the reviewed snapshot before it can become an inline comment.
     *
     * @param id       run-local identifier
     * @param body     Markdown finding text
     * @param path     requested file path, or {@code null} for review-wide findings
     * @param side     requested diff side, or {@code null}
     * @param line     requested line number on {@code side}, or {@code null}
     * @param severity optional severity label
     * @param category optional category label
     */
    public record Finding(String id, String body, String path, DiffSide side, Integer line,
                         String severity, String category) {

        public Finding {
            Objects.requireNonNull(body, "body");
            body = body.strip();
            path = path == null || path.isBlank() ? null : path.strip();
        }

        public boolean hasLocation() {
            return path != null && side != null && line != null && line > 0;
        }

        /** Identity used to collapse exact duplicates produced within a single run. */
        String dedupKey() {
            return path + "|" + side + "|" + line + "|" + body;
        }
    }
}
