package org.remus.giteabot.review.grounding;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.remus.giteabot.admin.AiClientFactory;
import org.remus.giteabot.admin.AiIntegration;
import org.remus.giteabot.admin.AiIntegrationService;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.audit.PrAuditEvent;
import org.remus.giteabot.audit.PrAuditEventService;
import org.remus.giteabot.prworkflow.WorkflowCancelledException;
import org.remus.giteabot.repository.model.DiffSide;
import org.remus.giteabot.review.ReviewDocument;
import org.remus.giteabot.review.ReviewFence;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Independent batched verification of quote-checked candidates, without publishing or state mutations. */
@Service
public class DoubleCheckService {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final Pattern HUNK = Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$");
    private static final String PROMPT = loadPrompt();

    private final AiIntegrationService integrationService;
    private final AiClientFactory clientFactory;
    private final PrAuditEventService auditService;

    public DoubleCheckService(AiIntegrationService integrationService, AiClientFactory clientFactory,
                              PrAuditEventService auditService) {
        this.integrationService = integrationService;
        this.clientFactory = clientFactory;
        this.auditService = auditService;
    }

    /** IDs must be globally unique after review-chunk merge. Results retain those IDs, including skipped candidates. */
    public Map<String, DoubleCheckOutcome> check(List<Candidate> candidates, Evidence evidence,
                                               DoubleCheckSettings settings, AiClient reviewClient,
                                               Budget budget, PrAuditEvent warningEvent, ReviewFence fence) {
        Map<String, DoubleCheckOutcome> outcomes = new LinkedHashMap<>();
        List<Candidate> selected = new ArrayList<>();
        for (Candidate candidate : candidates) {
            String id = candidate.finding().id();
            if (id == null || id.isBlank() || outcomes.containsKey(id)) {
                throw new IllegalArgumentException("Candidate IDs must be non-blank and globally unique");
            }
            outcomes.put(id, new DoubleCheckOutcome(Verdict.SKIPPED, "Not selected for double-check"));
            if (candidate.prior() ? candidate.contextChanged() || !candidate.disputeText().isBlank()
                    : settings.selects(candidate.finding().severity())) {
                if (candidate.prior() && !evidence.pinned()) continue;
                selected.add(candidate);
            }
        }
        if (selected.isEmpty()) return Map.copyOf(outcomes);
        fence.requireActive("before resolving double-check integration");
        AiClient client = resolveClient(settings, reviewClient, warningEvent, evidence);
        List<Hunk> hunks = hunks(evidence.diff());
        List<Map<String, Object>> batch = new ArrayList<>();
        int calls = 0;
        for (Candidate candidate : selected) {
            Map<String, Object> packaged;
            try {
                packaged = packageCandidate(candidate, evidence, hunks, 15);
                if (request(List.of(packaged), evidence, hunks).length() > budget.maxCharsPerChunk()) {
                    packaged = packageCandidate(candidate, evidence, hunks, 5);
                }
            } catch (WorkflowCancelledException e) {
                throw e;
            } catch (RuntimeException e) {
                outcomes.put(candidate.finding().id(), failure("Evidence unavailable"));
                continue;
            }
            if (request(List.of(packaged), evidence, hunks).length() > budget.maxCharsPerChunk()) {
                outcomes.put(candidate.finding().id(), new DoubleCheckOutcome(Verdict.UNCERTAIN,
                        "Evidence exceeds double-check budget after context trimming"));
                continue;
            }
            List<Map<String, Object>> combined = new ArrayList<>(batch);
            combined.add(packaged);
            if (!batch.isEmpty() && request(combined, evidence, hunks).length() > budget.maxCharsPerChunk()) {
                runBatch(batch, evidence, hunks, client, budget, calls++, outcomes, fence);
                batch.clear();
            }
            batch.add(packaged);
        }
        if (!batch.isEmpty()) runBatch(batch, evidence, hunks, client, budget, calls, outcomes, fence);
        return Map.copyOf(outcomes);
    }

    private AiClient resolveClient(DoubleCheckSettings settings, AiClient reviewClient,
                                   PrAuditEvent warningEvent, Evidence evidence) {
        if (settings.aiIntegrationId() == null) return reviewClient;
        AiIntegration integration = integrationService.findById(settings.aiIntegrationId()).orElse(null);
        if (integration != null) return clientFactory.getClient(integration);
        auditService.recordGroundingWarning(warningEvent, "double-check-config",
                "AI integration " + settings.aiIntegrationId() + " not found; using review integration",
                evidence.headSha(), evidence.effectiveBaseSha());
        return reviewClient;
    }

    private void runBatch(List<Map<String, Object>> batch, Evidence evidence, List<Hunk> hunks,
                          AiClient client, Budget budget, int call, Map<String, DoubleCheckOutcome> outcomes,
                          ReviewFence fence) {
        if (call >= budget.maxChunks()) {
            batch.forEach(item -> outcomes.put((String) item.get("id"), new DoubleCheckOutcome(Verdict.UNCERTAIN,
                    "Double-check chunk limit reached")));
            return;
        }
        fence.requireActive("before requesting finding double-check");
        Set<String> ids = new HashSet<>();
        batch.forEach(item -> ids.add((String) item.get("id")));
        try {
            String response = client.submitReviewPrompt(PROMPT, null, request(batch, evidence, hunks));
            outcomes.putAll(parse(response, ids));
        } catch (WorkflowCancelledException e) {
            throw e;
        } catch (RuntimeException e) {
            ids.forEach(id -> outcomes.put(id, failure("Double-check call failed")));
        }
    }

    private static Map<String, DoubleCheckOutcome> parse(String response, Set<String> ids) {
        Map<String, DoubleCheckOutcome> results = new LinkedHashMap<>();
        ids.forEach(id -> results.put(id, failure("Missing double-check verdict")));
        try {
            JsonNode root = MAPPER.readTree(response);
            if (root == null || !root.isObject() || !root.path("verdicts").isArray()) {
                throw new IllegalArgumentException("Expected verdicts array");
            }
            Set<String> seen = new HashSet<>();
            for (JsonNode item : root.path("verdicts")) {
                JsonNode idNode = item.path("id");
                if (!idNode.isTextual() || !ids.contains(idNode.asText())) continue;
                String id = idNode.asText();
                if (!seen.add(id)) {
                    results.put(id, failure("Duplicate double-check verdict"));
                    continue;
                }
                String value = item.path("verdict").asText("");
                JsonNode reason = item.path("reason");
                Verdict verdict = switch (value) {
                    case "keep" -> Verdict.KEEP;
                    case "drop" -> Verdict.DROP;
                    case "uncertain" -> Verdict.UNCERTAIN;
                    default -> Verdict.FAILED;
                };
                results.put(id, verdict == Verdict.FAILED || !reason.isTextual() || reason.asText().isBlank()
                        ? failure("Invalid double-check verdict") : new DoubleCheckOutcome(verdict, reason.asText()));
            }
        } catch (IOException | RuntimeException e) {
            ids.forEach(id -> results.put(id, failure("Malformed double-check response")));
        }
        return results;
    }

    private static Map<String, Object> packageCandidate(Candidate candidate, Evidence evidence,
                                                       List<Hunk> hunks, int radius) {
        ReviewDocument.Finding finding = candidate.finding();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", finding.id());
        item.put("severity", finding.severity());
        item.put("category", finding.category());
        item.put("body", finding.body());
        item.put("path", finding.path());
        item.put("side", finding.side());
        item.put("line", finding.line());
        item.put("quote", finding.quote());
        item.put("prior", candidate.prior());
        item.put("contextChanged", candidate.contextChanged());
        item.put("disputeText", candidate.disputeText());
        item.put("diffHunks", relevantHunks(hunks, finding.path(), finding.side(), candidate.matchedSpan()));
        if (evidence.pinned() && finding.hasLocation()) {
            PinnedSourceReader reader = finding.side() == DiffSide.OLD ? evidence.effectiveBase() : evidence.head();
            item.put("sourceContext", context(reader.read(finding.path()), candidate.matchedSpan(), finding.line(), radius));
        }
        List<Map<String, Object>> citations = new ArrayList<>();
        for (ReviewDocument.Citation citation : finding.citations()) {
            Map<String, Object> support = new LinkedHashMap<>();
            support.put("path", citation.path());
            support.put("quote", citation.quote());
            support.put("diffHunks", relevantHunks(hunks, citation.path(), null, null));
            if (evidence.pinned()) {
                QuoteMatch match = new QuoteVerifier().verifyCitation(citation, evidence.head());
                if (match.matched()) {
                    support.put("sourceContext", context(evidence.head().read(citation.path()), match.matchedSpan(), 1, radius));
                }
            }
            citations.add(support);
        }
        item.put("citations", citations);
        return item;
    }

    private static String context(String source, QuoteMatch.MatchedSpan span, int anchor, int radius) {
        String[] lines = source.split("\\R", -1);
        int start = Math.max(1, (span == null ? anchor : span.startLine()) - radius);
        int end = Math.min(lines.length, (span == null ? anchor : span.endLine()) + radius);
        StringBuilder text = new StringBuilder();
        for (int line = start; line <= end; line++) text.append(line).append(": ").append(lines[line - 1]).append('\n');
        return text.toString();
    }

    private static String request(List<Map<String, Object>> batch, Evidence evidence, List<Hunk> hunks) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("evidenceMode", evidence.pinned() ? "pinned" : "diff-only: no pinned revision available");
        request.put("headSha", evidence.headSha());
        request.put("effectiveBaseSha", evidence.effectiveBaseSha());
        request.put("changedFiles", hunks.stream().map(hunk -> Map.of("oldPath", hunk.oldPath(),
                "path", hunk.path(), "header", hunk.header())).distinct().toList());
        request.put("findings", batch);
        try {
            return MAPPER.writeValueAsString(request);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot serialize double-check evidence", e);
        }
    }

    private static List<String> relevantHunks(List<Hunk> hunks, String path, DiffSide side,
                                              QuoteMatch.MatchedSpan span) {
        if (path == null) return List.of();
        return hunks.stream().filter(hunk -> path.equals(hunk.path()) || path.equals(hunk.oldPath()))
                .filter(hunk -> span == null || side == null || hunk.header().isEmpty()
                        || (side == DiffSide.OLD ? hunk.oldStart() <= span.endLine()
                        && hunk.oldEnd() >= span.startLine() : hunk.start() <= span.endLine()
                        && hunk.end() >= span.startLine()))
                .map(Hunk::text).toList();
    }

    private static List<Hunk> hunks(String diff) {
        List<Hunk> result = new ArrayList<>();
        String oldPath = "";
        String path = "";
        String header = "";
        StringBuilder text = new StringBuilder();
        int oldStart = 0, oldEnd = 0, start = 0, end = 0;
        for (String line : diff.split("\\R")) {
            Matcher matcher = HUNK.matcher(line);
            if (line.startsWith("diff --git ") || matcher.matches()) {
                if (!path.isEmpty()) result.add(new Hunk(oldPath, path, header, text.toString(), oldStart, oldEnd, start, end));
                text.setLength(0);
                header = "";
                if (line.startsWith("diff --git ")) {
                    String[] paths = line.substring(11).split(" b/", 2);
                    oldPath = paths[0].replaceFirst("^a/", "");
                    path = paths.length == 2 ? paths[1] : oldPath;
                } else {
                    header = line;
                    oldStart = Integer.parseInt(matcher.group(1));
                    oldEnd = oldStart + (matcher.group(2) == null ? 1 : Integer.parseInt(matcher.group(2))) - 1;
                    start = Integer.parseInt(matcher.group(3));
                    end = start + (matcher.group(4) == null ? 1 : Integer.parseInt(matcher.group(4))) - 1;
                }
            }
            text.append(line).append('\n');
        }
        if (!path.isEmpty()) result.add(new Hunk(oldPath, path, header, text.toString(), oldStart, oldEnd, start, end));
        return result;
    }

    private static String loadPrompt() {
        try {
            return new ClassPathResource("prompts/double-check.md").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load double-check prompt", e);
        }
    }

    private static DoubleCheckOutcome failure(String reason) {
        return new DoubleCheckOutcome(Verdict.FAILED, reason);
    }

    /** disputeText contains new non-bot claims; callers omit already-answered text when context is unchanged. */
    public record Candidate(ReviewDocument.Finding finding, QuoteMatch.MatchedSpan matchedSpan,
                            boolean prior, boolean contextChanged, String disputeText) {
        public Candidate {
            Objects.requireNonNull(finding, "finding");
            disputeText = disputeText == null ? "" : disputeText;
        }
    }

    /** The base reader must represent the effective diff base, not the target branch tip. */
    public record Evidence(PinnedSourceReader head, PinnedSourceReader effectiveBase, String diff) {
        public Evidence { diff = diff == null ? "" : diff; }
        public boolean pinned() { return head != null && effectiveBase != null; }
        public String headSha() { return pinned() ? head.revisionSha() : null; }
        public String effectiveBaseSha() { return pinned() ? effectiveBase.revisionSha() : null; }
    }

    /** Evidence-character and call limits sourced from the bot's review chunk configuration. */
    public record Budget(int maxCharsPerChunk, int maxChunks) {
        public Budget {
            if (maxCharsPerChunk <= 0 || maxChunks <= 0) throw new IllegalArgumentException("Positive budgets required");
        }
    }

    public enum Verdict { KEEP, DROP, UNCERTAIN, FAILED, SKIPPED }

    public record DoubleCheckOutcome(Verdict verdict, String reason) {
        public boolean failed() { return verdict == Verdict.FAILED; }
        public boolean held() { return verdict == Verdict.UNCERTAIN; }
    }

    private record Hunk(String oldPath, String path, String header, String text,
                        int oldStart, int oldEnd, int start, int end) { }
}
