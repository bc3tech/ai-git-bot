package org.remus.giteabot.review.grounding;

import org.remus.giteabot.repository.model.DiffSide;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts the changed and context lines represented by a unified diff for unpinned checks. */
public final class DiffTextSource {

    private static final Pattern HUNK = Pattern.compile("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*$");
    private final Map<String, Map<DiffSide, List<DiffLine>>> files;

    public DiffTextSource(String diff) {
        this.files = parse(diff == null ? "" : diff);
    }

    /** Returns included lines for a file and diff side; omitted lines between hunks are never synthesized. */
    public List<DiffLine> lines(String path, DiffSide side) {
        if (path == null || side == null) {
            return List.of();
        }
        return files.getOrDefault(path, Map.of()).getOrDefault(side, List.of());
    }

    private static Map<String, Map<DiffSide, List<DiffLine>>> parse(String diff) {
        Map<String, EnumMap<DiffSide, List<DiffLine>>> files = new HashMap<>();
        String oldPath = null;
        String newPath = null;
        int oldLine = 0;
        int newLine = 0;
        boolean inHunk = false;

        for (String line : diff.split("\\R", -1)) {
            if (line.startsWith("diff --git ")) {
                oldPath = null;
                newPath = null;
                inHunk = false;
            } else if (!inHunk && line.startsWith("--- ")) {
                oldPath = diffPath(line.substring(4));
                inHunk = false;
            } else if (!inHunk && line.startsWith("+++ ")) {
                newPath = diffPath(line.substring(4));
                inHunk = false;
            } else {
                Matcher hunk = HUNK.matcher(line);
                if (hunk.matches()) {
                    oldLine = Integer.parseInt(hunk.group(1));
                    newLine = Integer.parseInt(hunk.group(2));
                    inHunk = true;
                } else if (inHunk && !line.isEmpty()) {
                    char marker = line.charAt(0);
                    String content = line.substring(1);
                    if (marker == ' ' || marker == '-') {
                        if (oldPath != null) {
                            add(files, oldPath, DiffSide.OLD, oldLine, content);
                        }
                        oldLine++;
                    }
                    if (marker == ' ' || marker == '+') {
                        if (newPath != null) {
                            add(files, newPath, DiffSide.NEW, newLine, content);
                        }
                        newLine++;
                    }
                    if (marker != ' ' && marker != '-' && marker != '+') {
                        inHunk = false;
                    }
                }
            }
        }

        Map<String, Map<DiffSide, List<DiffLine>>> immutable = new HashMap<>();
        files.forEach((path, sides) -> {
            EnumMap<DiffSide, List<DiffLine>> sideLists = new EnumMap<>(DiffSide.class);
            sides.forEach((side, lines) -> sideLists.put(side, List.copyOf(lines)));
            immutable.put(path, Collections.unmodifiableMap(sideLists));
        });
        return Map.copyOf(immutable);
    }

    private static void add(Map<String, EnumMap<DiffSide, List<DiffLine>>> files, String path,
                            DiffSide side, int lineNumber, String content) {
        files.computeIfAbsent(path, ignored -> new EnumMap<>(DiffSide.class))
                .computeIfAbsent(side, ignored -> new ArrayList<>())
                .add(new DiffLine(lineNumber, content));
    }

    private static String diffPath(String headerPath) {
        String path = headerPath.split("\\t", 2)[0];
        if (path.equals("/dev/null")) {
            return null;
        }
        if (path.startsWith("a/") || path.startsWith("b/")) {
            return path.substring(2);
        }
        return path;
    }

    public record DiffLine(int lineNumber, String content) {
    }
}
