package org.remus.giteabot.repository.model;

import java.util.Objects;

/**
 * An inline review comment whose location was validated against a {@link ReviewSnapshot}.
 *
 * @param findingId run-local identifier of the finding this comment publishes
 * @param path      file path in the head version (or the base path for deleted files)
 * @param oldPath   file path in the base version (differs from {@code path} for renames)
 * @param side      diff side the comment targets
 * @param line      line number on {@code side}
 * @param oldLine   base line number when the anchored line exists in the base version, else {@code null}
 * @param newLine   head line number when the anchored line exists in the head version, else {@code null}
 * @param body      Markdown comment body
 */
public record ReviewAnchorComment(String findingId, String path, String oldPath, DiffSide side, int line,
                                  Integer oldLine, Integer newLine, String body) {

    public ReviewAnchorComment {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(body, "body");
        if (line <= 0) {
            throw new IllegalArgumentException("line must be positive");
        }
        if (oldPath == null) {
            oldPath = path;
        }
    }
}
