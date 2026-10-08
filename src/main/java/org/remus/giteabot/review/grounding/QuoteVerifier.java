package org.remus.giteabot.review.grounding;

import org.remus.giteabot.review.ReviewDocument;
import org.remus.giteabot.repository.model.DiffSide;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.remus.giteabot.review.grounding.QuoteMatch.FailureReason;
import static org.remus.giteabot.review.grounding.QuoteMatch.MatchedSpan;

/** Deterministically checks quoted code against pinned source or the available unified diff. */
public final class QuoteVerifier {

    private static final int ANCHOR_WINDOW = 10;
    private static final int TIGHT_ANCHOR_WINDOW = 3;
    private static final int CONTEXT_LINES = 5;
    private static final int MIN_NON_WHITESPACE_CHARACTERS = 8;

    /** Verifies a new line-anchored finding against the matching pinned side. */
    public QuoteMatch verify(ReviewDocument.Finding finding, PinnedSourceReader headReader,
                             PinnedSourceReader effectiveBaseReader) {
        return verify(finding, headReader, effectiveBaseReader, false);
    }

    /** Verifies an existing finding, allowing a unique whole-file relocation when requested. */
    public QuoteMatch verifyPrior(ReviewDocument.Finding finding, PinnedSourceReader headReader,
                                  PinnedSourceReader effectiveBaseReader) {
        return verify(finding, headReader, effectiveBaseReader, true);
    }

    /** Verifies a line-anchored finding; the base reader must be for the effective base SHA. */
    public QuoteMatch verify(ReviewDocument.Finding finding, PinnedSourceReader headReader,
                             PinnedSourceReader effectiveBaseReader, boolean allowRelocation) {
        if (finding == null || missingQuote(finding.quote())) {
            return QuoteMatch.failure(FailureReason.QUOTE_MISSING);
        }
        if (!finding.hasLocation()) {
            return QuoteMatch.failure(FailureReason.INVALID_ANCHOR);
        }
        if (headReader == null || effectiveBaseReader == null) {
            throw new IllegalArgumentException("Both pinned revisions are required");
        }
        PinnedSourceReader reader = finding.side() == DiffSide.NEW ? headReader : effectiveBaseReader;
        return verifyAnchored(finding.path(), finding.quote(), finding.line(), toSourceLines(reader.read(finding.path())),
                allowRelocation);
    }

    /** Verifies a review-wide citation, which must occur exactly once in its cited pinned file. */
    public QuoteMatch verifyCitation(ReviewDocument.Citation citation, PinnedSourceReader reader) {
        if (citation == null || missingQuote(citation.quote()) || citation.path() == null || citation.path().isBlank()) {
            return QuoteMatch.failure(FailureReason.QUOTE_MISSING);
        }
        if (reader == null) {
            throw new IllegalArgumentException("A pinned reader is required");
        }
        return verifyUnique(citation.quote(), toSourceLines(reader.read(citation.path())));
    }

    /** Verifies a new finding against changed/context diff text when either revision SHA is unavailable. */
    public QuoteMatch verifyUnpinned(ReviewDocument.Finding finding, DiffTextSource source) {
        if (finding == null || missingQuote(finding.quote())) {
            return QuoteMatch.failure(FailureReason.QUOTE_MISSING);
        }
        if (finding.path() == null || finding.side() == null || source == null) {
            return QuoteMatch.failure(FailureReason.INVALID_ANCHOR);
        }
        List<SourceLine> lines = source.lines(finding.path(), finding.side()).stream()
                .map(line -> new SourceLine(line.lineNumber(), line.content())).toList();
        return verifyUnique(finding.quote(), lines);
    }

    /** Verifies an unpinned review-wide citation against the diff text for its path. */
    public QuoteMatch verifyCitationUnpinned(ReviewDocument.Citation citation, DiffTextSource source) {
        if (citation == null || missingQuote(citation.quote()) || citation.path() == null
                || citation.path().isBlank() || source == null) {
            return QuoteMatch.failure(FailureReason.QUOTE_MISSING);
        }
        List<SourceLine> headLines = source.lines(citation.path(), DiffSide.NEW).stream()
                .map(line -> new SourceLine(line.lineNumber(), line.content())).toList();
        List<SourceLine> baseLines = source.lines(citation.path(), DiffSide.OLD).stream()
                .map(line -> new SourceLine(line.lineNumber(), line.content())).toList();
        QuoteMatch headMatch = verifyUnique(citation.quote(), headLines);
        QuoteMatch baseMatch = verifyUnique(citation.quote(), baseLines);
        if (headMatch.matched() && baseMatch.matched()) {
            return QuoteMatch.failure(FailureReason.QUOTE_AMBIGUOUS);
        }
        if (headMatch.matched()) {
            return headMatch;
        }
        if (baseMatch.matched()) {
            return baseMatch;
        }
        if (headMatch.failureReason() == FailureReason.QUOTE_AMBIGUOUS
                || baseMatch.failureReason() == FailureReason.QUOTE_AMBIGUOUS) {
            return QuoteMatch.failure(FailureReason.QUOTE_AMBIGUOUS);
        }
        if (headMatch.failureReason() == FailureReason.QUOTE_TOO_WEAK
                || baseMatch.failureReason() == FailureReason.QUOTE_TOO_WEAK) {
            return QuoteMatch.failure(FailureReason.QUOTE_TOO_WEAK);
        }
        return QuoteMatch.failure(FailureReason.QUOTE_NOT_FOUND);
    }

    private static QuoteMatch verifyAnchored(String path, String quote, int anchorLine, List<SourceLine> source,
                                             boolean allowRelocation) {
        if (path == null || path.isBlank() || anchorLine < 1) {
            return QuoteMatch.failure(FailureReason.INVALID_ANCHOR);
        }
        List<String> quotedLines = normalizedQuote(quote);
        if (quotedLines.isEmpty()) {
            return QuoteMatch.failure(FailureReason.QUOTE_MISSING);
        }
        if (!isStrongEnough(quotedLines)) {
            return QuoteMatch.failure(FailureReason.QUOTE_TOO_WEAK);
        }
        List<Occurrence> occurrences = occurrences(quotedLines, source);
        if (occurrences.isEmpty()) {
            return QuoteMatch.failure(FailureReason.QUOTE_NOT_FOUND);
        }
        List<Occurrence> nearby = occurrences.stream()
                .filter(occurrence -> distance(occurrence.span(), anchorLine) <= ANCHOR_WINDOW).toList();
        if (nearby.size() > 1) {
            return QuoteMatch.failure(FailureReason.QUOTE_AMBIGUOUS);
        }
        if (nearby.size() == 1) {
            Occurrence occurrence = nearby.getFirst();
            if (occurrence.span().startLine() <= anchorLine && occurrence.span().endLine() >= anchorLine
                    || Math.abs(occurrence.span().endLine() - anchorLine) <= TIGHT_ANCHOR_WINDOW) {
                return QuoteMatch.success(occurrence.span(), contextHash(source, occurrence));
            }
            return QuoteMatch.failure(FailureReason.QUOTE_OUT_OF_WINDOW);
        }
        if (allowRelocation) {
            if (occurrences.size() == 1) {
                Occurrence occurrence = occurrences.getFirst();
                return QuoteMatch.success(occurrence.span(), contextHash(source, occurrence));
            }
            return QuoteMatch.failure(FailureReason.QUOTE_AMBIGUOUS);
        }
        return QuoteMatch.failure(FailureReason.QUOTE_MISATTRIBUTED);
    }

    private static QuoteMatch verifyUnique(String quote, List<SourceLine> source) {
        List<String> quotedLines = normalizedQuote(quote);
        if (quotedLines.isEmpty()) {
            return QuoteMatch.failure(FailureReason.QUOTE_MISSING);
        }
        if (!isStrongEnough(quotedLines)) {
            return QuoteMatch.failure(FailureReason.QUOTE_TOO_WEAK);
        }
        List<Occurrence> occurrences = occurrences(quotedLines, source);
        if (occurrences.isEmpty()) {
            return QuoteMatch.failure(FailureReason.QUOTE_NOT_FOUND);
        }
        if (occurrences.size() != 1) {
            return QuoteMatch.failure(FailureReason.QUOTE_AMBIGUOUS);
        }
        Occurrence occurrence = occurrences.getFirst();
        return QuoteMatch.success(occurrence.span(), contextHash(source, occurrence));
    }

    private static List<Occurrence> occurrences(List<String> quote, List<SourceLine> source) {
        List<Occurrence> matches = new ArrayList<>();
        for (int start = 0; start <= source.size() - quote.size(); start++) {
            boolean matchesQuote = true;
            for (int offset = 0; offset < quote.size(); offset++) {
                SourceLine current = source.get(start + offset);
                if (offset > 0 && current.lineNumber() != source.get(start + offset - 1).lineNumber() + 1
                        || !normalize(current.content()).equals(quote.get(offset))) {
                    matchesQuote = false;
                    break;
                }
            }
            if (matchesQuote) {
                matches.add(new Occurrence(start, start + quote.size() - 1,
                        new MatchedSpan(source.get(start).lineNumber(), source.get(start + quote.size() - 1).lineNumber())));
            }
        }
        return matches;
    }

    private static String contextHash(List<SourceLine> source, Occurrence occurrence) {
        int start = Math.max(0, occurrence.startIndex() - CONTEXT_LINES);
        int end = Math.min(source.size() - 1, occurrence.endIndex() + CONTEXT_LINES);
        String context = String.join("\n", source.subList(start, end + 1).stream()
                .map(line -> normalize(line.content()))
                .toList());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(context.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static List<String> normalizedQuote(String quote) {
        if (missingQuote(quote)) {
            return List.of();
        }
        return java.util.Arrays.stream(quote.strip().split("\\R", -1))
                .map(QuoteVerifier::normalize)
                .toList();
    }

    private static boolean isStrongEnough(List<String> lines) {
        return lines.stream().anyMatch(line -> line.replaceAll("\\s", "").length() >= MIN_NON_WHITESPACE_CHARACTERS);
    }

    private static boolean missingQuote(String quote) {
        return quote == null || quote.isBlank();
    }

    private static String normalize(String line) {
        return line == null ? "" : line.strip();
    }

    private static int distance(MatchedSpan span, int line) {
        if (line < span.startLine()) {
            return span.startLine() - line;
        }
        return Math.max(0, line - span.endLine());
    }

    private static List<SourceLine> toSourceLines(String content) {
        if (content.isEmpty()) {
            return List.of();
        }
        String[] lines = content.split("\\R", -1);
        int count = lines.length;
        if (count > 0 && lines[count - 1].isEmpty() && content.matches("(?s).*(?:\\r\\n|\\r|\\n)$")) {
            count--;
        }
        List<SourceLine> source = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            source.add(new SourceLine(i + 1, lines[i]));
        }
        return source;
    }

    private record SourceLine(int lineNumber, String content) {
    }

    private record Occurrence(int startIndex, int endIndex, MatchedSpan span) {
    }
}
