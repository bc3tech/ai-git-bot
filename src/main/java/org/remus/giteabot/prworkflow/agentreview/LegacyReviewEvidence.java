package org.remus.giteabot.prworkflow.agentreview;

import org.remus.giteabot.agent.model.ImplementationPlan;
import org.remus.giteabot.agent.validation.ToolResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Delivered coverage survives eviction; availability describes only the final outbound snapshot. */
final class LegacyReviewEvidence {
    private static final Pattern NUMBERED_LINE = Pattern.compile("^(\\d+) \\| ?(.*)$", Pattern.DOTALL);
    private final Map<Key, Entry> entries = new LinkedHashMap<>();
    private final Map<Source, Coverage> coverage = new HashMap<>();
    private int nextId;
    private long sequence;

    record Key(String ref, String operation, List<String> args) {
        Key {
            operation = operation == null ? "" : operation;
            args = java.util.Collections.unmodifiableList(new ArrayList<>(args));
        }

        static Key file(String ref, String path) {
            return new Key(ref, "requestFiles", java.util.Collections.singletonList(path));
        }

        static Key tool(String ref, ImplementationPlan.ToolRequest request) {
            return new Key(ref, request.getTool(),
                    request.getArgs() == null ? List.of() : request.getArgs());
        }

        Source source() {
            return (operation.equals("requestFiles") || operation.equals("cat")) && !args.isEmpty()
                    ? new Source(ref, args.getFirst()) : null;
        }

        boolean fullFile() {
            return operation.equals("requestFiles")
                    || (operation.equals("cat") && (args.size() == 1
                    || (args.size() == 2 && "1".equals(args.get(1)))));
        }
    }

    private record Source(String ref, String path) {}
    private record Interval(int first, int last) {}
    private record Selection(String block, ReviewAgentStrategy.FileEvidence source) {}

    private static final class Coverage {
        boolean complete;
        Integer totalLines;
        final List<Interval> intervals = new ArrayList<>();

        Coverage copy() {
            Coverage copy = new Coverage();
            copy.complete = complete;
            copy.totalLines = totalLines;
            copy.intervals.addAll(intervals);
            return copy;
        }

        void add(ReviewAgentStrategy.FileEvidence evidence) {
            if (!evidence.success()) return;
            if (evidence.complete()) complete = true;
            if (evidence.totalLines() != null) totalLines = evidence.totalLines();
            int count = verifiedLineCount(evidence);
            if (count > 0) intervals.add(new Interval(evidence.startLine(), evidence.startLine() + count - 1));
            if (!complete && totalLines != null) {
                intervals.sort(Comparator.comparingInt(Interval::first));
                int next = 1;
                for (Interval interval : intervals) {
                    if (interval.first() > next) break;
                    next = Math.max(next, interval.last() + 1);
                }
                complete = next > totalLines;
            }
        }
    }

    private static final class Entry {
        final int id;
        final Key key;
        long recency;
        boolean acquired;
        boolean delivered;
        ReviewAgentStrategy.FileEvidence candidate;
        ReviewAgentStrategy.FileEvidence retainedSource;
        String retained;

        Entry(int id, Key key) {
            this.id = id;
            this.key = key;
        }
    }

    void requested(Key key) {
        entries.computeIfAbsent(key, ignored -> new Entry(++nextId, key));
    }

    boolean available(Key key) {
        Entry entry = entries.get(key);
        return entry != null && entry.retained != null;
    }

    void acquired(Key key, ReviewAgentStrategy.FileEvidence evidence) {
        requested(key);
        Entry entry = entries.get(key);
        entry.recency = ++sequence;
        entry.candidate = evidence.success() ? evidence : null;
    }

    private static boolean satisfies(Key key, ReviewAgentStrategy.FileEvidence evidence) {
        boolean satisfied = evidence.success() && evidence.complete();
        if (evidence.success() && key.operation().equals("cat") && !key.fullFile()
                && key.args().size() >= 2) {
            try {
                int first = Integer.parseInt(key.args().get(1));
                Integer last = key.args().size() == 3 ? Integer.valueOf(key.args().get(2))
                        : evidence.totalLines();
                if (last != null && evidence.totalLines() != null) {
                    last = Math.min(last, evidence.totalLines());
                }
                satisfied = evidence.startLine() <= first && last != null
                        && evidence.startLine() + verifiedLineCount(evidence) - 1 >= last;
            } catch (NumberFormatException ignored) {
                satisfied = false;
            }
        }
        return satisfied;
    }

    /** Called upon the next model response, never during provisional or rejected retry selection. */
    void commitDelivery() {
        for (Entry entry : entries.values()) {
            if (entry.retainedSource == null) continue;
            entry.delivered = true;
            entry.acquired |= satisfies(entry.key, entry.retainedSource);
            if (entry.key.source() != null) {
                coverage.computeIfAbsent(entry.key.source(), ignored -> new Coverage())
                        .add(entry.retainedSource);
            }
        }
    }

    Map<Key, String> availability() {
        Map<Key, String> result = new HashMap<>();
        entries.forEach((key, entry) -> {
            if (entry.retained != null) result.put(key, entry.retained);
        });
        return result;
    }

    boolean delivered(Set<Key> executed, Map<Key, String> before) {
        return executed.stream().anyMatch(key -> available(key)
                && !entries.get(key).retained.equals(before.get(key)));
    }

    /**
     * Select newest acquisitions first. Whole serialized blocks, not just source characters,
     * count against the optional cap. The predicate checks the complete provider request.
     */
    void select(int maxChars, Predicate<String> fits) {
        List<Entry> newest = new ArrayList<>(entries.values());
        newest.sort(Comparator.comparingLong((Entry entry) -> entry.recency).reversed());
        Map<Entry, String> selected = new LinkedHashMap<>();
        Map<Entry, ReviewAgentStrategy.FileEvidence> selectedSources = new HashMap<>();
        int remaining = Math.max(0, maxChars);
        for (Entry entry : newest) {
            ReviewAgentStrategy.FileEvidence source = entry.candidate != null
                    ? entry.candidate : entry.retainedSource;
            Selection selection = entry.candidate == null && entry.retained != null
                    && entry.retained.length() <= remaining
                    ? new Selection(entry.retained, entry.retainedSource)
                    : source != null ? excerpt(entry, source, remaining) : null;
            String block = selection == null ? null : selection.block();
            if (block == null || remaining <= 0) continue;
            int allowance = remaining;
            while (block != null) {
                selected.put(entry, block);
                if (fits.test(render(selected))) break;
                selected.remove(entry);
                allowance /= 2;
                selection = excerpt(entry, source, allowance);
                block = selection == null ? null : selection.block();
            }
            if (block != null) {
                remaining -= block.length();
                selectedSources.put(entry, selection.source());
            }
        }
        entries.values().forEach(entry -> {
            entry.retained = selected.get(entry);
            entry.retainedSource = selectedSources.get(entry);
            entry.candidate = null;
        });
    }

    String snapshot() {
        Map<Entry, String> selected = new LinkedHashMap<>();
        entries.values().forEach(entry -> {
            if (entry.retained != null) selected.put(entry, entry.retained);
        });
        return render(selected);
    }

    String inventory() {
        StringBuilder inventory = new StringBuilder("Evidence inventory:\n");
        int shown = 0;
        for (Entry entry : entries.values()) {
            String status = entry.retained != null ? "retained"
                    : entry.delivered ? "delivered-evicted" : "requested-undelivered";
            String item = "E" + entry.id + " " + status + " " + singleLine(entry.key.operation())
                    + " " + singleLine(String.join(" ", entry.key.args()))
                    + " ref=" + singleLine(entry.key.ref()) + "\n";
            if (inventory.length() + item.length() > 1_024) {
                item = "E" + entry.id + " " + status + " (source label omitted)\n";
            }
            if (inventory.length() + item.length() > 1_024) break;
            inventory.append(item);
            shown++;
        }
        if (shown < entries.size()) inventory.append(entries.size() - shown)
                .append(" additional entries omitted; refetch unavailable evidence explicitly.\n");
        return inventory.toString();
    }

    private static String render(Map<Entry, String> selected) {
        StringBuilder result = new StringBuilder();
        selected.entrySet().stream().sorted(Comparator.comparingInt(item -> item.getKey().id))
                .forEach(item -> result.append(item.getValue()));
        return result.toString();
    }

    List<String> coverageNotes() {
        Map<Source, Coverage> effective = new HashMap<>();
        coverage.forEach((source, delivered) -> effective.put(source, delivered.copy()));
        entries.values().forEach(entry -> {
            if (entry.key.source() != null && entry.retainedSource != null) {
                effective.computeIfAbsent(entry.key.source(), ignored -> new Coverage())
                        .add(entry.retainedSource);
            }
        });
        long unresolved = entries.values().stream().filter(entry -> {
            boolean satisfied = entry.acquired || (entry.retainedSource != null
                    && satisfies(entry.key, entry.retainedSource));
            if (entry.key.source() == null) return !satisfied;
            Coverage sourceCoverage = effective.get(entry.key.source());
            if (sourceCoverage != null && sourceCoverage.complete) return false;
            if (entry.key.fullFile()) return true;
            return !satisfied;
        }).count();
        return unresolved == 0 ? List.of() : List.of("Repository evidence acquisition remains incomplete for "
                + unresolved + " requested read(s). Failed or partial source reads limit this review.");
    }

    private static Selection excerpt(Entry entry, ReviewAgentStrategy.FileEvidence evidence, int allowance) {
        String label = "### E" + entry.id + " source=" + singleLine(entry.key.operation())
                + " " + singleLine(String.join(" ", entry.key.args()))
                + " ref=" + singleLine(entry.key.ref()) + "\n";
        List<String> lines = lines(evidence.content());
        String suffix = "Omitted regions: request narrower cat path startLine endLine at the same ref; "
                + "repeating this request cannot restore omitted lines.\n";
        int payloadBudget = allowance - label.length() - suffix.length() - 100;
        if (payloadBudget < 1) return null;
        if (lines.isEmpty()) {
            String block = label + (evidence.complete() ? "(verified empty source/result)\n"
                    : "(no usable source lines)\n");
            return evidence.complete() && block.length() <= allowance ? new Selection(block, evidence) : null;
        }
        StringBuilder body = new StringBuilder();
        StringBuilder retainedContent = new StringBuilder();
        int included = 0;
        boolean clippedLine = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = (evidence.startLine() + i) + " | " + lines.get(i) + "\n";
            if (body.length() + line.length() > payloadBudget) {
                if (included == 0 && payloadBudget > 45) {
                    body.append(line, 0, payloadBudget - 35).append(" [line excerpt truncated]\n");
                    int labelLength = Integer.toString(evidence.startLine() + i).length() + 3;
                    int retainedLength = Math.max(0, payloadBudget - 35 - labelLength);
                    retainedContent.append(lines.get(i), 0, Math.min(retainedLength, lines.get(i).length()));
                    included++;
                    clippedLine = true;
                }
                break;
            }
            body.append(line);
            retainedContent.append(lines.get(i));
            if (i < lines.size() - 1 || !hasPartialLastLine(evidence)) retainedContent.append('\n');
            included++;
        }
        if (included == 0) return null;
        clippedLine |= included == lines.size() && hasPartialLastLine(evidence);
        boolean omitted = clippedLine || included < lines.size() || !evidence.complete();
        int last = evidence.startLine() + included - 1;
        String status = "Lines " + evidence.startLine() + "-" + last
                + (clippedLine ? " (last line partial)" : "")
                + "; total=" + (evidence.totalLines() == null ? "unknown" : evidence.totalLines()) + "\n";
        if (omitted) {
            status += "Omitted: "
                    + (evidence.startLine() > 1 ? "1-" + (evidence.startLine() - 1) + ", " : "")
                    + (clippedLine ? "remainder of line " + last + ", " : "")
                    + (evidence.totalLines() == null ? (last + 1) + "-unknown EOF"
                    : last < evidence.totalLines() ? (last + 1) + "-" + evidence.totalLines()
                    : "no further verified lines")
                    + "\n";
        }
        String block = label + status + body + (omitted ? suffix : "Complete acquired result retained.\n");
        ReviewAgentStrategy.FileEvidence retained = new ReviewAgentStrategy.FileEvidence(true,
                !omitted, retainedContent.toString(), evidence.startLine(), evidence.totalLines());
        return block.length() <= allowance ? new Selection(block, retained) : null;
    }

    int retainedSourceCharacters() {
        return entries.values().stream().filter(entry -> entry.retainedSource != null)
                .mapToInt(entry -> entry.retainedSource.content().length()).sum();
    }

    private static String singleLine(String value) {
        return value == null ? "(none)" : value.replace('\n', ' ').replace('\r', ' ');
    }

    private static List<String> lines(String content) {
        if (content == null || content.isEmpty()) return List.of();
        List<String> result = new ArrayList<>(List.of(content.split("\\r\\n|\\n|\\r", -1)));
        if (result.getLast().isEmpty()) result.removeLast();
        return result;
    }

    private static boolean hasPartialLastLine(ReviewAgentStrategy.FileEvidence evidence) {
        return !evidence.complete() && !evidence.content().isEmpty()
                && !evidence.content().endsWith("\n") && !evidence.content().endsWith("\r");
    }

    private static int verifiedLineCount(ReviewAgentStrategy.FileEvidence evidence) {
        return lines(evidence.content()).size() - (hasPartialLastLine(evidence) ? 1 : 0);
    }

    static ReviewAgentStrategy.FileEvidence toolEvidence(Key key, ToolResult result) {
        if (!result.success()) return ReviewAgentStrategy.FileEvidence.failed();
        String output = result.output() == null ? "" : result.output();
        boolean truncated = output.contains("... (output truncated)");
        if (!key.operation().equals("cat")) {
            return new ReviewAgentStrategy.FileEvidence(true, !truncated, output, 1, null);
        }
        int requestedStart = 1;
        try {
            if (key.args().size() > 1) requestedStart = Integer.parseInt(key.args().get(1));
        } catch (NumberFormatException ignored) {
            return ReviewAgentStrategy.FileEvidence.failed();
        }
        if (output.isEmpty()) {
            return key.fullFile() ? ReviewAgentStrategy.FileEvidence.complete("")
                    : new ReviewAgentStrategy.FileEvidence(true, false, "", requestedStart, null);
        }
        if (output.strip().equals("No content in requested line range.")) {
            return key.fullFile() ? ReviewAgentStrategy.FileEvidence.complete("")
                    : new ReviewAgentStrategy.FileEvidence(true, false, "", requestedStart, null);
        }
        StringBuilder source = new StringBuilder();
        int expected = requestedStart;
        for (String line : lines(output)) {
            Matcher matcher = NUMBERED_LINE.matcher(line);
            if (!matcher.matches() || Integer.parseInt(matcher.group(1)) != expected) {
                truncated = true;
                break;
            }
            source.append(matcher.group(2)).append('\n');
            expected++;
        }
        if (truncated && !source.isEmpty()) {
            // A character-capped cat output can end in a numbered but incomplete source line.
            int previousNewline = source.lastIndexOf("\n", source.length() - 2);
            source.setLength(previousNewline < 0 ? 0 : previousNewline + 1);
            expected--;
        }
        if (source.isEmpty()) return ReviewAgentStrategy.FileEvidence.failed();
        boolean toEof = key.args().size() <= 2;
        if (key.args().size() == 3 && !truncated) {
            try {
                toEof = expected - 1 < Integer.parseInt(key.args().get(2));
            } catch (NumberFormatException ignored) {
                return ReviewAgentStrategy.FileEvidence.failed();
            }
        }
        Integer total = !truncated && toEof ? expected - 1 : null;
        return new ReviewAgentStrategy.FileEvidence(true, !truncated && key.fullFile(),
                source.toString(), requestedStart, total);
    }
}
