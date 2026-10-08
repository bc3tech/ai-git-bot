package org.remus.giteabot.review;

import org.remus.giteabot.repository.model.DiffSide;
import org.remus.giteabot.repository.model.ReviewAnchorComment;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a unified (git) diff into exact old/new line coordinates so a model-requested
 * finding location can be validated. Only lines that appear in a well-formed hunk are
 * addressable; binary, truncated, or malformed file sections are never commentable and no
 * nearest-line guessing is performed.
 */
public final class ReviewDiffPositionParser {

    private static final Pattern HUNK_HEADER =
            Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*");
    private static final String DEV_NULL = "/dev/null";

    private ReviewDiffPositionParser() {
    }

    public static DiffPositions parse(String diff) {
        List<FileDiff> files = new ArrayList<>();
        if (diff == null || diff.isEmpty()) {
            return new DiffPositions(files);
        }
        FileBuilder current = null;
        int oldNo = 0;
        int newNo = 0;
        int remainingOld = 0;
        int remainingNew = 0;

        String[] rawLines = diff.split("\n", -1);
        for (int index = 0; index < rawLines.length; index++) {
            String rawLine = rawLines[index];
            String line = rawLine.endsWith("\r") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
            boolean inHunk = remainingOld > 0 || remainingNew > 0;
            if (inHunk && current != null) {
                // A blank line mid-hunk is a context line whose leading space was stripped in transit, but
                // the trailing element after the final newline means the diff was cut short: never infer
                // a coordinate from it.
                boolean truncated = line.isEmpty() && index == rawLines.length - 1;
                char c = truncated ? '\0' : line.isEmpty() ? ' ' : line.charAt(0);
                switch (c) {
                    case ' ' -> {
                        current.newLines.put(newNo, oldNo);
                        current.oldLines.put(oldNo, newNo);
                        oldNo++;
                        newNo++;
                        remainingOld--;
                        remainingNew--;
                    }
                    case '-' -> {
                        current.oldLines.put(oldNo, null);
                        oldNo++;
                        remainingOld--;
                    }
                    case '+' -> {
                        current.newLines.put(newNo, null);
                        newNo++;
                        remainingNew--;
                    }
                    case '\\' -> {
                        // "\ No newline at end of file" carries no coordinates.
                    }
                    default -> {
                        current.malformed = true;
                        remainingOld = 0;
                        remainingNew = 0;
                    }
                }
                if (remainingOld < 0 || remainingNew < 0) {
                    current.malformed = true;
                    remainingOld = 0;
                    remainingNew = 0;
                }
                if (c == ' ' || c == '-' || c == '+' || c == '\\') {
                    continue;
                }
            }

            if (line.startsWith("diff --git ")) {
                if (current != null) {
                    if (remainingOld > 0 || remainingNew > 0) current.malformed = true;
                    files.add(current.build());
                }
                current = new FileBuilder();
                remainingOld = 0;
                remainingNew = 0;
                continue;
            }
            if (line.startsWith("--- ") && (current == null || current.hunks > 0 || current.oldSeen)) {
                if (current != null) files.add(current.build());
                current = new FileBuilder();
            }
            if (current == null) {
                if (line.startsWith("@@")) {
                    // Hunk without file headers (e.g. a chunk that starts mid-file): no safe coordinates.
                    current = new FileBuilder();
                    current.malformed = true;
                } else {
                    continue;
                }
            }
            if (line.startsWith("--- ")) {
                current.oldSeen = true;
                current.oldPath = parsePath(line.substring(4), "a/");
                current.oldIsNull = current.oldPath == null;
            } else if (line.startsWith("+++ ")) {
                current.newPath = parsePath(line.substring(4), "b/");
                current.newIsNull = current.newPath == null;
            } else if (line.startsWith("rename from ")) {
                current.renameFrom = unquote(line.substring("rename from ".length()));
            } else if (line.startsWith("rename to ")) {
                current.renameTo = unquote(line.substring("rename to ".length()));
            } else if (line.startsWith("Binary files ") || line.startsWith("GIT binary patch")) {
                current.binary = true;
            } else if (line.startsWith("@@")) {
                Matcher m = HUNK_HEADER.matcher(line);
                if (!m.matches() || (!current.oldSeen && current.newPath == null)) {
                    current.malformed = true;
                    continue;
                }
                current.hunks++;
                oldNo = Integer.parseInt(m.group(1));
                remainingOld = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
                newNo = Integer.parseInt(m.group(3));
                remainingNew = m.group(4) == null ? 1 : Integer.parseInt(m.group(4));
            } else if (current.hunks > 0 && !line.isEmpty()
                    && (line.charAt(0) == '+' || line.charAt(0) == '-' || line.charAt(0) == ' ')) {
                // Content lines beyond the counts announced by the hunk header.
                current.malformed = true;
            }
        }
        if (current != null) {
            if (remainingOld > 0 || remainingNew > 0) current.malformed = true;
            files.add(current.build());
        }
        return new DiffPositions(files);
    }

    static String parsePath(String raw, String prefix) {
        String value = raw;
        if (!value.startsWith("\"")) {
            int tab = value.indexOf('\t');
            if (tab >= 0) value = value.substring(0, tab);
        }
        value = unquote(value);
        if (value == null || value.equals(DEV_NULL)) {
            return null;
        }
        return value.startsWith(prefix) ? value.substring(prefix.length()) : value;
    }

    /** Decodes git's C-style quoted path ({@code "a/caf\303\251 \"x\".txt"}). */
    static String unquote(String raw) {
        if (raw == null) return null;
        String value = raw.strip();
        if (value.length() < 2 || !value.startsWith("\"") || !value.endsWith("\"")) {
            return value;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chars = value.substring(1, value.length() - 1).getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < chars.length; i++) {
            byte b = chars[i];
            if (b != '\\' || i + 1 >= chars.length) {
                out.write(b);
                continue;
            }
            byte next = chars[++i];
            switch (next) {
                case 'n' -> out.write('\n');
                case 't' -> out.write('\t');
                case 'r' -> out.write('\r');
                case 'a' -> out.write(7);
                case 'b' -> out.write('\b');
                case 'f' -> out.write('\f');
                case 'v' -> out.write(11);
                case '"', '\\' -> out.write(next);
                default -> {
                    if (i + 2 < chars.length && isOctal(next) && isOctal(chars[i + 1]) && isOctal(chars[i + 2])) {
                        out.write(Integer.parseInt(new String(chars, i, 3, StandardCharsets.US_ASCII), 8));
                        i += 2;
                    } else {
                        out.write('\\');
                        out.write(next);
                    }
                }
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static boolean isOctal(byte b) {
        return b >= '0' && b <= '7';
    }

    private static final class FileBuilder {
        String oldPath;
        String newPath;
        String renameFrom;
        String renameTo;
        boolean oldSeen;
        boolean oldIsNull;
        boolean newIsNull;
        boolean binary;
        boolean malformed;
        int hunks;
        final Map<Integer, Integer> oldLines = new HashMap<>();
        final Map<Integer, Integer> newLines = new HashMap<>();

        FileDiff build() {
            String oldP = oldIsNull ? null : (oldPath != null ? oldPath : renameFrom);
            String newP = newIsNull ? null : (newPath != null ? newPath : renameTo);
            boolean commentable = !binary && !malformed && hunks > 0 && (oldP != null || newP != null);
            return new FileDiff(oldP, newP, commentable,
                    Collections.unmodifiableMap(oldLines), Collections.unmodifiableMap(newLines));
        }
    }

    /**
     * Coordinates of one file in the diff.
     *
     * @param oldPath  base path, {@code null} for added files
     * @param newPath  head path, {@code null} for deleted files
     * @param commentable whether lines of this file can be anchored at all
     * @param oldLines base line -> head line (for context lines) or {@code null} (removed lines)
     * @param newLines head line -> base line (for context lines) or {@code null} (added lines)
     */
    public record FileDiff(String oldPath, String newPath, boolean commentable,
                           Map<Integer, Integer> oldLines, Map<Integer, Integer> newLines) {

        /** Path reviewers see: the head path, or the base path for deleted files. */
        public String displayPath() {
            return newPath != null ? newPath : oldPath;
        }
    }

    /** Parsed positions of a whole diff. */
    public static final class DiffPositions {
        private final Map<String, FileDiff> byPath = new LinkedHashMap<>();
        private final Map<String, FileDiff> byOldPath = new LinkedHashMap<>();

        DiffPositions(List<FileDiff> files) {
            for (FileDiff f : files) {
                if (f.displayPath() != null) byPath.putIfAbsent(f.displayPath(), f);
                if (f.oldPath() != null) byOldPath.putIfAbsent(f.oldPath(), f);
            }
        }

        public Optional<FileDiff> file(String path) {
            if (path == null) return Optional.empty();
            for (String candidate : candidates(path)) {
                FileDiff f = byPath.get(candidate);
                if (f == null) f = byOldPath.get(candidate);
                if (f != null) return Optional.of(f);
            }
            return Optional.empty();
        }

        /**
         * Validates a requested finding location. Returns an anchor only when the exact
         * line exists on the requested side of a commentable file; never a nearby line.
         */
        public Optional<ReviewAnchorComment> anchor(ReviewDocument.Finding finding, String body) {
            if (finding == null || !finding.hasLocation()) return Optional.empty();
            Optional<FileDiff> file = file(finding.path());
            if (file.isEmpty() || !file.get().commentable()) return Optional.empty();
            FileDiff f = file.get();
            int line = finding.line();
            if (finding.side() == DiffSide.NEW) {
                if (f.newPath() == null || !f.newLines().containsKey(line)) return Optional.empty();
                Integer oldLine = f.newLines().get(line);
                return Optional.of(new ReviewAnchorComment(finding.id(), f.newPath(),
                        f.oldPath() != null ? f.oldPath() : f.newPath(), DiffSide.NEW, line, oldLine, line, body));
            }
            if (f.oldPath() == null || !f.oldLines().containsKey(line)) return Optional.empty();
            Integer newLine = f.oldLines().get(line);
            return Optional.of(new ReviewAnchorComment(finding.id(), f.displayPath(), f.oldPath(),
                    DiffSide.OLD, line, line, newLine, body));
        }

        public boolean isEmpty() {
            return byPath.isEmpty() && byOldPath.isEmpty();
        }

        private static List<String> candidates(String path) {
            String p = path.strip().replace('\\', '/');
            List<String> result = new ArrayList<>();
            result.add(p);
            String trimmed = p;
            while (trimmed.startsWith("./") || trimmed.startsWith("/")) {
                trimmed = trimmed.startsWith("./") ? trimmed.substring(2) : trimmed.substring(1);
            }
            if (!trimmed.equals(p)) result.add(trimmed);
            if (trimmed.startsWith("a/") || trimmed.startsWith("b/")) result.add(trimmed.substring(2));
            return result;
        }
    }
}
