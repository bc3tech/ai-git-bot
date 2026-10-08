package org.remus.giteabot.review.grounding;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.audit.AuditEventType;
import org.remus.giteabot.audit.PrAuditEvent;
import org.remus.giteabot.audit.PrAuditEventService;
import org.remus.giteabot.repository.model.DiffSide;
import org.remus.giteabot.review.ReviewDocument;
import org.remus.giteabot.review.ReviewFence;
import org.remus.giteabot.review.state.ReviewFindingRecord;
import org.remus.giteabot.review.state.ReviewFindingStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.remus.giteabot.review.grounding.DoubleCheckService.*;

@Service
public class FindingGroundingService {
    public static final String PARSE_FAILURE = "review output could not be parsed; no findings posted";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final QuoteVerifier verifier = new QuoteVerifier();
    private final DoubleCheckService doubleCheck;
    private final PrAuditEventService audit;

    public FindingGroundingService(DoubleCheckService doubleCheck, PrAuditEventService audit) {
        this.doubleCheck = doubleCheck;
        this.audit = audit;
    }

    /** No provider or operational-state writes. Dispositions are audited here; withdrawals on successful execution. */
    public GroundingResult ground(ReviewDocument document, Evidence source, PriorFindingsDigest digest,
                                  List<ReviewFindingRecord> dedupPriors, DoubleCheckSettings settings,
                                  AiClient client, Budget budget, PrAuditEvent event, ReviewFence fence) {
        try {
            return groundInternal(document, source, digest, dedupPriors, settings, client, budget, event, fence);
        } catch (org.remus.giteabot.review.state.ReviewRunLeaseService.LeaseLostException e) {
            audit.recordGroundingWarning(event, "GROUNDING", "lease lost", source.headSha(), source.effectiveBaseSha());
            throw e;
        }
    }

    private GroundingResult groundInternal(ReviewDocument document, Evidence source, PriorFindingsDigest digest,
                                  List<ReviewFindingRecord> dedupPriors, DoubleCheckSettings settings,
                                  AiClient client, Budget budget, PrAuditEvent event, ReviewFence fence) {
        fence.requireActive("before grounding findings");
        List<String> notes = new ArrayList<>();
        List<FindingDisposition> dispositions = new ArrayList<>();
        List<ThreadAction> actions = new ArrayList<>();
        Map<String, GroundingResult.FindingEvidence> metadata = new LinkedHashMap<>();
        Map<String, List<UUID>> supersedes = new LinkedHashMap<>();
        if (!document.structured()) {
            audit.recordGroundingWarning(event, "PARSE", PARSE_FAILURE, source.headSha(), source.effectiveBaseSha());
            return new GroundingResult(List.of(), List.of(PARSE_FAILURE), List.of(), List.of(), Map.of(), Map.of(), true);
        }
        boolean degraded = !source.pinned();
        if (!source.pinned()) notes.add("Findings could not be anchored to a pinned revision; diff-only evidence was checked.");
        DiffTextSource diff = new DiffTextSource(source.diff());
        List<Candidate> candidates = new ArrayList<>();
        for (ReviewDocument.Finding input : document.findings()) {
            ReviewDocument.Finding finding = copy(input, UUID.randomUUID().toString(), input.path(), false);
            Verification verified = verify(finding, source, diff, false);
            if (!verified.match().matched()) {
                disposition(dispositions, finding, AuditEventType.FINDING_DROPPED, "QUOTE",
                        verified.match().failureReason().name());
                continue;
            }
            metadata.put(finding.id(), verified.evidence());
            candidates.add(new Candidate(finding, verified.match().matchedSpan(), false, false, ""));
        }
        Map<String, PriorFindingsDigest.PriorFinding> checkedPriors = new LinkedHashMap<>();
        Map<String, String> renames = renames(source.diff());
        if (source.pinned() && digest.commentsComplete()) {
            for (PriorFindingsDigest.PriorFinding prior : digest.findings()) {
                ReviewFindingRecord record = prior.finding();
                if (record.getStatus() != ReviewFindingStatus.PUBLISHED) continue;
                try {
                    ReviewDocument.Finding finding = priorFinding(record, renames);
                    Verification verified = verify(finding, source, diff, true);
                    if (!verified.match().matched()) {
                        actions.add(new ThreadAction.Withdraw(prior, "quote gone: " + verified.match().failureReason()));
                    } else if (prior.hasNewDisputeText()
                            || !Objects.equals(record.getContextHash(), verified.evidence().contextHash())) {
                        checkedPriors.put(finding.id(), prior);
                        candidates.add(new Candidate(finding, verified.match().matchedSpan(), true,
                                !Objects.equals(record.getContextHash(), verified.evidence().contextHash()),
                                prior.hasNewDisputeText() ? prior.disputeText() : ""));
                    }
                } catch (IllegalArgumentException e) {
                    notes.add("Prior finding evidence unavailable; prior left unchanged.");
                    degraded = true;
                }
            }
        }
        candidates.sort(java.util.Comparator.comparing(Candidate::prior).reversed());
        Map<String, DoubleCheckOutcome> outcomes;
        try {
            outcomes = doubleCheck.check(candidates, source, settings, client, budget, event, fence);
        } catch (org.remus.giteabot.review.state.ReviewRunLeaseService.LeaseLostException
                 | org.remus.giteabot.prworkflow.WorkflowCancelledException e) {
            throw e;
        } catch (RuntimeException e) {
            outcomes = new LinkedHashMap<>();
            for (Candidate candidate : candidates) outcomes.put(candidate.finding().id(),
                    new DoubleCheckOutcome(Verdict.FAILED, "Double-check unavailable"));
        }
        List<ReviewDocument.Finding> publish = new ArrayList<>();
        for (Candidate candidate : candidates) {
            ReviewDocument.Finding finding = candidate.finding();
            DoubleCheckOutcome outcome = outcomes.getOrDefault(finding.id(),
                    new DoubleCheckOutcome(Verdict.FAILED, "Missing double-check verdict"));
            if (outcome.failed() || outcome.held()) {
                degraded = true;
                notes.add(outcome.failed() ? "Double-check unavailable; only verified BLOCKER findings may be posted."
                        : "Uncertain findings were held for a later review.");
                if (candidate.prior() || outcome.held() || DoubleCheckSettings.Severity.parse(finding.severity()) != DoubleCheckSettings.Severity.BLOCKER) {
                    disposition(dispositions, finding, AuditEventType.FINDING_HELD, "DOUBLE_CHECK", outcome.reason());
                    continue;
                }
                finding = new ReviewDocument.Finding(finding.id(), finding.body() + "\n\n> Not double-checked.",
                        finding.path(), finding.side(), finding.line(), finding.severity(), finding.category(),
                        finding.quote(), finding.citations());
            }
            if (candidate.prior()) {
                PriorFindingsDigest.PriorFinding prior = checkedPriors.get(finding.id());
                if (outcome.verdict() == Verdict.DROP) actions.add(new ThreadAction.Withdraw(prior, outcome.reason()));
                else if (outcome.verdict() == Verdict.KEEP && prior.hasNewDisputeText())
                    actions.add(new ThreadAction.UpheldReply(prior, outcome.reason()));
                continue;
            }
            if (outcome.verdict() == Verdict.DROP) {
                disposition(dispositions, finding, AuditEventType.FINDING_DROPPED, "DOUBLE_CHECK", outcome.reason());
                continue;
            }
            GroundingResult.FindingEvidence evidence = metadata.get(finding.id());
            ReviewDocument.Finding current = finding;
            boolean duplicate = dedupPriors.stream().anyMatch(prior -> sameIssue(current, evidence, prior, renames,
                    source.pinned()));
            if (!duplicate) duplicate = publish.stream().anyMatch(other -> Objects.equals(other.category(), current.category())
                    && Objects.equals(other.path(), current.path()) && other.side() == current.side()
                    && Objects.equals(metadata.get(other.id()).quoteHash(), evidence.quoteHash())
                    && Objects.equals(metadata.get(other.id()).contextHash(), evidence.contextHash()));
            if (duplicate) {
                disposition(dispositions, finding, AuditEventType.FINDING_DROPPED, "DEDUP", "Unchanged prior finding");
                continue;
            }
            if (source.pinned() && digest.commentsComplete() && !outcome.failed()) {
                Map<String, DoubleCheckOutcome> checked = outcomes;
                List<UUID> replaced = digest.findings().stream().map(PriorFindingsDigest.PriorFinding::finding)
                        .filter(prior -> prior.getStatus() == ReviewFindingStatus.PUBLISHED
                                && Objects.equals(renames.getOrDefault(prior.getPath(), prior.getPath()), current.path())
                                && Objects.equals(prior.getSide(), current.side() == null ? null : current.side().name())
                                && Objects.equals(prior.getCategory(), current.category())
                                && (Objects.equals(prior.getQuoteHash(), evidence.quoteHash())
                                    || prior.getLine() != null && current.line() != null
                                    && Math.abs(prior.getLine() - current.line()) <= 3)
                                && !Objects.equals(prior.getContextHash(), evidence.contextHash())
                                && (!checkedPriors.containsKey(prior.getId().toString())
                                    || checked.containsKey(prior.getId().toString())
                                    && (checked.get(prior.getId().toString()).verdict() == Verdict.KEEP
                                        || checked.get(prior.getId().toString()).verdict() == Verdict.SKIPPED))
                                && actions.stream().noneMatch(a -> a instanceof ThreadAction.Withdraw
                                        && !a.reason().startsWith("quote gone:")
                                        && a.prior().finding().getId().equals(prior.getId())))
                        .map(ReviewFindingRecord::getId).toList();
                if (!replaced.isEmpty()) {
                    supersedes.put(finding.id(), replaced);
                    actions.removeIf(a -> replaced.contains(a.prior().finding().getId()));
                }
            }
            publish.add(source.pinned() ? finding : copy(finding, finding.id(), finding.path(), true));
        }
        for (FindingDisposition disposition : dispositions) {
            ReviewDocument.Finding finding = disposition.finding();
            audit.recordFindingDisposition(event, disposition.type(), finding.id(), finding.path(),
                    finding.side() == null ? null : finding.side().name(), finding.line(), auditQuote(finding),
                    finding.severity(), finding.category(), disposition.stage(), disposition.reason(),
                    source.headSha(), source.effectiveBaseSha());
        }
        return new GroundingResult(publish, notes.stream().distinct().toList(), actions, dispositions, metadata, supersedes, degraded);
    }

    private Verification verify(ReviewDocument.Finding finding, Evidence source, DiffTextSource diff, boolean prior) {
        if (finding.hasLocation() || finding.quote() != null && finding.path() != null) {
            QuoteMatch match;
            if (!source.pinned()) match = verifier.verifyUnpinned(finding, diff);
            else if (prior && !finding.hasLocation()) match = verifier.verifyCitation(
                    new ReviewDocument.Citation(finding.path(), finding.quote()),
                    finding.side() == DiffSide.OLD ? source.effectiveBase() : source.head());
            else match = prior ? verifier.verifyPrior(finding, source.head(), source.effectiveBase())
                    : verifier.verify(finding, source.head(), source.effectiveBase());
            for (ReviewDocument.Citation citation : finding.citations()) {
                QuoteMatch supporting = source.pinned() ? verifier.verifyCitation(citation, source.head())
                        : verifier.verifyCitationUnpinned(citation, diff);
                if (!supporting.matched()) return new Verification(supporting, null);
            }
            return new Verification(match, new GroundingResult.FindingEvidence(hash(normalize(finding.quote())),
                    match.contextHash(), finding.quote()));
        }
        if (finding.citations().isEmpty()) return new Verification(QuoteMatch.failure(QuoteMatch.FailureReason.QUOTE_MISSING), null);
        List<String> contexts = new ArrayList<>();
        QuoteMatch last = null;
        for (ReviewDocument.Citation citation : finding.citations()) {
            last = source.pinned() ? verifier.verifyCitation(citation, source.head()) : verifier.verifyCitationUnpinned(citation, diff);
            if (!last.matched()) return new Verification(last, null);
            contexts.add(last.contextHash());
        }
        try {
            String stored = MAPPER.writeValueAsString(finding.citations());
            String quoteHash = citationHash(finding.citations());
            return new Verification(last, new GroundingResult.FindingEvidence(quoteHash,
                    hash(String.join("\n", contexts.stream().sorted().toList())), stored));
        } catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    static ReviewDocument.Finding priorFinding(ReviewFindingRecord record, Map<String, String> renames) {
        List<ReviewDocument.Citation> citations = List.of();
        String quote = record.getQuote();
        if (record.getPath() == null && quote != null) {
            try { citations = MAPPER.readValue(quote, new TypeReference<List<ReviewDocument.Citation>>() {}); }
            catch (JsonProcessingException e) { throw new IllegalArgumentException("Prior citations unavailable", e); }
            citations = citations.stream().map(c -> new ReviewDocument.Citation(renames.getOrDefault(c.path(), c.path()), c.quote())).toList();
            quote = null;
        }
        return new ReviewDocument.Finding(record.getId().toString(), record.getBody(),
                renames.getOrDefault(record.getPath(), record.getPath()), record.getSide() == null ? null : DiffSide.valueOf(record.getSide()),
                record.getLine(), record.getSeverity(), record.getCategory(), quote, citations);
    }

    private static boolean sameIssue(ReviewDocument.Finding finding, GroundingResult.FindingEvidence evidence,
                                     ReviewFindingRecord prior, Map<String, String> renames, boolean pinned) {
        String priorQuoteHash = prior.getQuoteHash();
        if (prior.getPath() == null && !renames.isEmpty()) {
            try { priorQuoteHash = citationHash(priorFinding(prior, renames).citations()); }
            catch (IllegalArgumentException e) { return false; }
        }
        return (prior.getStatus() == ReviewFindingStatus.PUBLISHED || prior.getStatus() == ReviewFindingStatus.GONE)
                && Objects.equals(finding.path(), renames.getOrDefault(prior.getPath(), prior.getPath()))
                && Objects.equals(finding.side() == null ? null : finding.side().name(), prior.getSide())
                && Objects.equals(finding.category(), prior.getCategory())
                && Objects.equals(evidence.quoteHash(), priorQuoteHash)
                && (!pinned || Objects.equals(evidence.contextHash(), prior.getContextHash()));
    }

    private static String auditQuote(ReviewDocument.Finding finding) {
        if (finding.quote() != null || finding.citations().isEmpty()) return finding.quote();
        try { return MAPPER.writeValueAsString(finding.citations()); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    private static String citationHash(List<ReviewDocument.Citation> citations) {
        return hash(citations.stream().map(c -> c.path() + "\n" + normalize(c.quote()))
                .sorted().reduce("", (a, b) -> a + "\n" + b));
    }

    private static ReviewDocument.Finding copy(ReviewDocument.Finding finding, String id, String path, boolean stripAnchor) {
        return new ReviewDocument.Finding(id, finding.body(), path, finding.side(), stripAnchor ? null : finding.line(),
                finding.severity(), finding.category(), finding.quote(), finding.citations());
    }

    private static void disposition(List<FindingDisposition> result, ReviewDocument.Finding finding,
                                    AuditEventType type, String stage, String reason) {
        result.add(new FindingDisposition(finding, type, stage, reason));
    }

    static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static String normalize(String quote) {
        return quote == null ? "" : String.join("\n", Arrays.stream(quote.strip().split("\\R", -1)).map(String::strip).toList());
    }

    private static Map<String, String> renames(String diff) {
        Map<String, String> result = new LinkedHashMap<>();
        String from = null;
        for (String line : diff.split("\\R")) {
            if (line.startsWith("diff --git ")) from = null;
            else if (line.startsWith("rename from ")) from = line.substring(12);
            else if (line.startsWith("rename to ") && from != null) result.put(from, line.substring(10));
        }
        return result;
    }

    private record Verification(QuoteMatch match, GroundingResult.FindingEvidence evidence) {}
}
