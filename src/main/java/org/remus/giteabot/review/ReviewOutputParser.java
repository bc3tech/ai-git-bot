package org.remus.giteabot.review;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.repository.model.DiffSide;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizes model review output into a {@link ReviewDocument}. Accepts the structured
 * envelope requested by {@link ReviewOutputInstructions} (fenced or bare). Anything else —
 * legacy Markdown or a malformed envelope — falls back to the original text as the summary so
 * no feedback is lost and no location is guessed.
 */
@Slf4j
public final class ReviewOutputParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonFactory FACTORY = MAPPER.getFactory();
    private static final Pattern FENCE = Pattern.compile(
            "```[ \\t]*(?:json|review|review-json)?[ \\t]*\\r?\\n(.*?)\\r?\\n[ \\t]*```", Pattern.DOTALL);
    private static final Pattern LINE_START_BRACE = Pattern.compile("(?m)^[ \\t]*\\{");

    private ReviewOutputParser() {
    }

    /**
     * Parse result.
     *
     * @param document normalized review
     * @param warning  diagnostic when structured output was expected but unusable, else {@code null}
     */
    public record Result(ReviewDocument document, String warning) {
    }

    public static Result parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new Result(ReviewDocument.text(""), null);
        }
        Envelope envelope = findEnvelope(raw);
        if (envelope == null) {
            return new Result(ReviewDocument.text(raw), null);
        }
        if (envelope.node == null) {
            String warning = "Structured review output could not be parsed; publishing it as text: "
                    + envelope.error;
            log.warn(warning);
            return new Result(ReviewDocument.text(raw), warning);
        }
        JsonNode root = envelope.node;
        String summary = text(root.get("summary"));
        List<ReviewDocument.Finding> findings = new ArrayList<>();
        int dropped = 0;
        JsonNode findingsNode = root.get("findings");
        if (findingsNode != null && findingsNode.isArray()) {
            for (JsonNode element : findingsNode) {
                ReviewDocument.Finding finding = toFinding(element, findings.size() + 1);
                if (finding == null) {
                    dropped++;
                } else {
                    findings.add(finding);
                }
            }
        }
        if (summary == null || summary.isBlank()) {
            String outside = (raw.substring(0, envelope.start) + raw.substring(envelope.end)).strip();
            summary = outside;
        }
        ReviewDocument document = new ReviewDocument(summary, dedupe(findings), true);
        if (document.isEmpty() && !raw.isBlank() && dropped > 0) {
            String warning = "Structured review output contained no usable findings; publishing it as text";
            log.warn(warning);
            return new Result(ReviewDocument.text(raw), warning);
        }
        String warning = dropped > 0
                ? dropped + " structured finding(s) without a body were ignored" : null;
        if (warning != null) {
            log.warn(warning);
        }
        return new Result(document, warning);
    }

    /**
     * Merges chunk documents produced within one run. Summaries are concatenated in order;
     * exact duplicate findings are collapsed and identifiers are reassigned run-locally.
     */
    public static ReviewDocument merge(List<ReviewDocument> documents) {
        if (documents == null || documents.isEmpty()) {
            return ReviewDocument.text("");
        }
        if (documents.size() == 1) {
            return documents.getFirst();
        }
        StringBuilder summary = new StringBuilder();
        List<ReviewDocument.Finding> findings = new ArrayList<>();
        boolean structured = true;
        for (ReviewDocument doc : documents) {
            if (!doc.summary().isBlank()) {
                if (!summary.isEmpty()) summary.append("\n\n");
                summary.append(doc.summary());
            }
            findings.addAll(doc.findings());
            structured &= doc.structured();
        }
        return new ReviewDocument(summary.toString(), dedupe(findings), structured);
    }

    private static List<ReviewDocument.Finding> dedupe(List<ReviewDocument.Finding> findings) {
        Set<String> seen = new LinkedHashSet<>();
        List<ReviewDocument.Finding> result = new ArrayList<>();
        for (ReviewDocument.Finding f : findings) {
            if (seen.add(f.dedupKey())) {
                result.add(new ReviewDocument.Finding("f" + (result.size() + 1), f.body(), f.path(), f.side(),
                        f.line(), f.severity(), f.category()));
            }
        }
        return result;
    }

    private static ReviewDocument.Finding toFinding(JsonNode element, int index) {
        if (element == null || !element.isObject()) {
            return null;
        }
        String body = firstText(element, "body", "message", "comment", "description");
        String title = text(element.get("title"));
        if ((body == null || body.isBlank()) && (title == null || title.isBlank())) {
            return null;
        }
        if (body == null || body.isBlank()) {
            body = title;
        } else if (title != null && !title.isBlank() && !body.contains(title)) {
            body = "**" + title.strip() + "**\n\n" + body;
        }
        String path = firstText(element, "path", "file");
        DiffSide side = DiffSide.parse(text(element.get("side")));
        JsonNode lineNode = element.get("line");
        Integer line = lineNode != null && lineNode.isIntegralNumber() && lineNode.canConvertToInt()
                ? lineNode.asInt() : null;
        return new ReviewDocument.Finding("f" + index, body, path, side, line,
                text(element.get("severity")), text(element.get("category")));
    }

    private static String firstText(JsonNode node, String... keys) {
        for (String key : keys) {
            String value = text(node.get(key));
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static String text(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }

    private record Envelope(int start, int end, JsonNode node, String error) {
    }

    /**
     * Locates the review envelope: the last fenced block or line-start JSON object that looks
     * like a review ({@code summary} or {@code findings} key, no severity-count keys).
     */
    private static Envelope findEnvelope(String raw) {
        Envelope found = null;
        Matcher fence = FENCE.matcher(raw);
        while (fence.find()) {
            Envelope candidate = tryParse(fence.group(1), fence.start(), fence.end());
            if (candidate != null) found = candidate;
        }
        if (found != null) {
            return found;
        }
        Matcher brace = LINE_START_BRACE.matcher(raw);
        while (brace.find()) {
            int start = raw.indexOf('{', brace.start());
            Envelope candidate = tryParseBare(raw, start);
            if (candidate != null) {
                found = candidate;
                if (candidate.node != null) break;
            }
        }
        return found;
    }

    private static Envelope tryParse(String json, int start, int end) {
        if (!looksLikeEnvelope(json)) return null;
        try {
            JsonNode node = MAPPER.readTree(json.strip());
            if (node == null || !node.isObject() || !isEnvelope(node)) return null;
            return new Envelope(start, end, node, null);
        } catch (Exception e) {
            return new Envelope(start, end, null, e.getMessage());
        }
    }

    private static Envelope tryParseBare(String raw, int start) {
        String tail = raw.substring(start);
        if (!looksLikeEnvelope(tail)) return null;
        try (JsonParser parser = FACTORY.createParser(tail)) {
            JsonNode node = MAPPER.readTree(parser);
            if (node == null || !node.isObject() || !isEnvelope(node)) return null;
            int end = start + (int) parser.currentLocation().getCharOffset();
            return new Envelope(start, end, node, null);
        } catch (Exception e) {
            return new Envelope(start, raw.length(), null, e.getMessage());
        }
    }

    private static boolean looksLikeEnvelope(String json) {
        return json.contains("\"findings\"") || json.contains("\"summary\"");
    }

    private static boolean isEnvelope(JsonNode node) {
        boolean reviewShape = (node.has("summary") && node.get("summary").isTextual())
                || (node.has("findings") && node.get("findings").isArray());
        boolean classification = node.has("blocker") || node.has("medium") || node.has("low");
        return reviewShape && !classification;
    }
}
