package org.remus.giteabot.repository.model;

/**
 * Side of a unified diff a review comment is anchored to. {@code OLD} addresses a
 * line number in the base version of the file (removed or context lines), {@code NEW}
 * a line number in the head version (added or context lines).
 */
public enum DiffSide {
    OLD,
    NEW;

    /** Parses {@code old}/{@code new} (also {@code left}/{@code right}), case-insensitively. */
    public static DiffSide parse(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "old", "left", "base", "deleted", "removed" -> OLD;
            case "new", "right", "head", "added" -> NEW;
            default -> null;
        };
    }
}
