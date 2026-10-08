package org.remus.giteabot.review.grounding;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.audit.AuditEventType;
import org.remus.giteabot.audit.PrAuditEvent;
import org.remus.giteabot.audit.PrAuditEventService;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.model.DiffSide;
import org.remus.giteabot.review.ReviewDocument;
import org.remus.giteabot.review.ReviewFence;
import org.remus.giteabot.review.state.ReviewFindingRecord;
import org.remus.giteabot.review.state.ReviewFindingStatus;
import org.remus.giteabot.review.state.ReviewRunLeaseService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.remus.giteabot.review.grounding.DoubleCheckService.*;

class FindingGroundingServiceTest {
    private static final String HEAD = "a".repeat(40);
    private static final String BASE = "b".repeat(40);
    private static final String QUOTE = "return response(428);";
    private static final String CODE = "before();\n" + QUOTE + "\nafter();\n";
    private static final String DIFF = "diff --git a/A.java b/A.java\n--- a/A.java\n+++ b/A.java\n@@ -1,3 +1,3 @@\n before();\n-return response(409);\n+" + QUOTE + "\n after();\n";
    private final RepositoryApiClient api = mock(RepositoryApiClient.class);
    private final DoubleCheckService check = mock(DoubleCheckService.class);
    private final PrAuditEventService audit = mock(PrAuditEventService.class);
    private final PrAuditEvent event = new PrAuditEvent();
    private final FindingGroundingService service = new FindingGroundingService(check, audit);
    private Function<Candidate, Verdict> verdict = c -> Verdict.KEEP;
    private List<Candidate> candidates = new ArrayList<>();

    @BeforeEach
    void setup() {
        when(api.getFileContent(anyString(), anyString(), anyString(), anyString())).thenReturn(CODE);
        when(check.check(anyList(), any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            candidates = inv.getArgument(0);
            return candidates.stream().collect(Collectors.toMap(c -> c.finding().id(),
                    c -> new DoubleCheckOutcome(verdict.apply(c), "Verified reason")));
        });
    }

    @Test
    void groundedFindingKept() {
        GroundingResult result = fresh(finding("bug", "HIGH", QUOTE));
        assertThat(result.publish()).hasSize(1);
        assertThat(result.degraded()).isFalse();
        assertThat(result.evidence().get(result.publish().getFirst().id()).contextHash()).hasSize(64);
    }

    @Test
    void fabricatedForceArgsDroppedAndAudited() {
        GroundingResult result = fresh(finding("bug", "BLOCKER", "force_args = args.force_args"));
        assertThat(result.publish()).isEmpty();
        assertThat(result.dispositions()).singleElement().satisfies(d -> {
            assertThat(d.type()).isEqualTo(AuditEventType.FINDING_DROPPED);
            assertThat(d.stage()).isEqualTo("QUOTE");
        });
        verify(audit).recordFindingDisposition(eq(event), eq(AuditEventType.FINDING_DROPPED), anyString(), eq("A.java"),
                eq("NEW"), eq(2), anyString(), eq("BLOCKER"), eq("bug"), eq("QUOTE"), anyString(), eq(HEAD), eq(BASE));
    }

    @Test
    void FindingDescribing409To428FixDroppedByDoubleCheck() {
        verdict = c -> Verdict.DROP;
        GroundingResult result = fresh(finding("bug", "HIGH", QUOTE));
        assertThat(result.publish()).isEmpty();
        assertThat(result.dispositions().getFirst().stage()).isEqualTo("DOUBLE_CHECK");
    }

    @Test
    void unchangedPriorSuppressedWithoutRecheck() {
        ReviewFindingRecord prior = stored();
        GroundingResult result = run(List.of(finding("bug", "HIGH", QUOTE)), digest(prior, false), List.of(prior), evidence(DIFF));
        assertThat(result.publish()).isEmpty();
        assertThat(result.dispositions().getFirst().stage()).isEqualTo("DEDUP");
        assertThat(candidates).noneMatch(Candidate::prior);
    }

    @Test
    void sameQuoteDifferentCategoriesBothKept() {
        assertThat(run(List.of(finding("bug", "HIGH", QUOTE), finding("security", "HIGH", QUOTE)), empty(), List.of(), evidence(DIFF))
                .publish()).hasSize(2);
    }

    @Test
    void duplicateWithinNewCandidatesSuppressed() {
        assertThat(run(List.of(finding("bug", "HIGH", QUOTE), finding("bug", "HIGH", QUOTE)), empty(), List.of(), evidence(DIFF))
                .publish()).hasSize(1);
    }

    @Test
    void disputedPriorUpheldOnce() {
        ReviewFindingRecord prior = stored();
        GroundingResult result = run(List.of(), digest(prior, true), List.of(prior), evidence(DIFF));
        assertThat(result.threadActions()).singleElement().isInstanceOf(ThreadAction.UpheldReply.class);
        assertThat(candidates).singleElement().satisfies(c -> assertThat(c.disputeText()).contains("human claim"));
        assertThat(run(List.of(), digest(prior, false), List.of(prior), evidence(DIFF)).threadActions()).isEmpty();
    }

    @Test
    void disputedPriorDroppedWithdraws() {
        ReviewFindingRecord prior = stored();
        verdict = c -> Verdict.DROP;
        assertThat(run(List.of(), digest(prior, true), List.of(prior), evidence(DIFF)).threadActions())
                .singleElement().isInstanceOf(ThreadAction.Withdraw.class);
    }

    @Test
    void quoteGoneWithdrawsEvenWhenNewDoubleCheckFails() {
        ReviewFindingRecord prior = stored();
        prior.setQuote("removedExpression();");
        verdict = c -> Verdict.FAILED;
        GroundingResult result = run(List.of(finding("other", "HIGH", QUOTE)), digest(prior, false), List.of(prior), evidence(DIFF));
        assertThat(result.threadActions()).singleElement().satisfies(a -> assertThat(a.reason()).startsWith("quote gone:"));
        assertThat(result.degraded()).isTrue();
    }

    @Test
    void newGuardChangesContextAndPriorIsRecheckedAndWithdrawn() {
        ReviewFindingRecord prior = stored();
        when(api.getFileContent(anyString(), anyString(), anyString(), anyString())).thenReturn("if (guard) return;\n" + CODE);
        verdict = c -> Verdict.DROP;
        GroundingResult result = run(List.of(), digest(prior, false), List.of(prior), evidence(DIFF));
        assertThat(candidates).singleElement().satisfies(c -> assertThat(c.contextChanged()).isTrue());
        assertThat(result.threadActions()).singleElement().isInstanceOf(ThreadAction.Withdraw.class);
    }

    @Test
    void changedContextAndPersistentIssuePublishesFreshAndSupersedesAfterDelivery() {
        ReviewFindingRecord prior = stored();
        prior.setContextHash("old-context");
        GroundingResult result = run(List.of(finding("bug", "HIGH", QUOTE)), digest(prior, false), List.of(prior), evidence(DIFF));
        assertThat(result.publish()).hasSize(1);
        assertThat(result.supersedes().values()).containsExactly(List.of(prior.getId()));
    }

    @Test
    void changedQuotePersistentIssueSupersedesInsteadOfWithdrawReply() {
        ReviewFindingRecord prior = stored();
        prior.setQuote("removedExpression();");
        prior.setContextHash("old-context");
        GroundingResult result = run(List.of(finding("bug", "HIGH", QUOTE)), digest(prior, false), List.of(prior), evidence(DIFF));
        assertThat(result.publish()).hasSize(1);
        assertThat(result.supersedes().values()).containsExactly(List.of(prior.getId()));
        assertThat(result.threadActions()).isEmpty();
    }

    @Test
    void renamedUnchangedFindingDeduped() {
        ReviewFindingRecord prior = stored();
        prior.setPath("Old.java");
        GroundingResult result = run(List.of(finding("bug", "HIGH", QUOTE)), digest(prior, false), List.of(prior),
                evidence("diff --git a/Old.java b/A.java\nrename from Old.java\nrename to A.java\n" + DIFF));
        assertThat(result.publish()).isEmpty();
    }

    @Test
    void gonePriorDedupsButGetsNoActions() {
        ReviewFindingRecord prior = stored();
        prior.setStatus(ReviewFindingStatus.GONE);
        GroundingResult result = run(List.of(finding("bug", "HIGH", QUOTE)), digest(prior, true), List.of(prior), evidence(DIFF));
        assertThat(result.publish()).isEmpty();
        assertThat(result.threadActions()).isEmpty();
    }

    @Test
    void doubleCheckFailureKeepsOnlyBlockerWithWarning() {
        verdict = c -> Verdict.FAILED;
        GroundingResult result = run(List.of(finding("bug", "BLOCKER", QUOTE), finding("other", "HIGH", QUOTE)),
                empty(), List.of(), evidence(DIFF));
        assertThat(result.publish()).singleElement().satisfies(f -> assertThat(f.body()).contains("Not double-checked"));
        assertThat(result.dispositions()).singleElement().satisfies(d -> assertThat(d.type()).isEqualTo(AuditEventType.FINDING_HELD));
        assertThat(result.degraded()).isTrue();
        assertThat(result.summaryNotes()).anyMatch(n -> n.contains("Double-check unavailable"));
    }

    @Test
    void failureOnPriorLeavesItUnchanged() {
        ReviewFindingRecord prior = stored();
        verdict = c -> Verdict.FAILED;
        assertThat(run(List.of(), digest(prior, true), List.of(prior), evidence(DIFF)).threadActions()).isEmpty();
    }

    @Test
    void uncertainBlockerHeld() {
        verdict = c -> Verdict.UNCERTAIN;
        GroundingResult result = fresh(finding("bug", "BLOCKER", QUOTE));
        assertThat(result.publish()).isEmpty();
        assertThat(result.degraded()).isTrue();
    }

    @Test
    void unstructuredOutputNeverPublished() {
        GroundingResult result = service.ground(ReviewDocument.text("dangerous unverified output"), evidence(DIFF), empty(),
                List.of(), DoubleCheckSettings.defaults(), mock(AiClient.class), new Budget(10000, 3), event, ReviewFence.NONE);
        assertThat(result.publish()).isEmpty();
        assertThat(result.summaryNotes()).containsExactly(FindingGroundingService.PARSE_FAILURE);
        assertThat(result.degraded()).isTrue();
        verifyNoInteractions(check);
        verify(audit).recordGroundingWarning(event, "PARSE", FindingGroundingService.PARSE_FAILURE, HEAD, BASE);
    }

    @Test
    void unpinnedUsesDiffAndNeverRechecksOrActionsPriors() {
        ReviewFindingRecord prior = stored();
        GroundingResult result = run(List.of(finding("other", "HIGH", QUOTE), finding("bug", "HIGH", "force_args = options;")),
                digest(prior, true), List.of(prior), new Evidence(null, null, DIFF));
        assertThat(result.publish()).singleElement().satisfies(f -> assertThat(f.hasLocation()).isFalse());
        assertThat(result.threadActions()).isEmpty();
        assertThat(candidates).noneMatch(Candidate::prior);
        assertThat(result.degraded()).isTrue();
    }

    @Test
    void unpinnedFailureOnlyDiffVerifiedBlockerSummaryOnly() {
        verdict = c -> Verdict.FAILED;
        GroundingResult result = run(List.of(finding("bug", "BLOCKER", QUOTE), finding("other", "HIGH", QUOTE)),
                empty(), List.of(), new Evidence(null, null, DIFF));
        assertThat(result.publish()).singleElement().satisfies(f -> {
            assertThat(f.hasLocation()).isFalse();
            assertThat(f.body()).contains("Not double-checked");
        });
    }

    @Test
    void incompleteScanMakesNoPriorActionsOrRechecks() {
        ReviewFindingRecord prior = stored();
        prior.setQuote("noLongerExists();");
        PriorFindingsDigest digest = new PriorFindingsDigest(digest(prior, true).findings(), false, null);
        GroundingResult result = run(List.of(), digest, List.of(prior), evidence(DIFF));
        assertThat(result.threadActions()).isEmpty();
        assertThat(candidates).isEmpty();
    }

    @Test
    void failedPinnedReadDoesNotWithdrawPrior() {
        ReviewFindingRecord prior = stored();
        when(api.getFileContent(anyString(), anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("offline"));
        GroundingResult result = run(List.of(), digest(prior, true), List.of(prior), evidence(DIFF));
        assertThat(result.threadActions()).isEmpty();
        assertThat(result.degraded()).isTrue();
        verify(audit).recordGroundingWarning(eq(event), eq("QUOTE"), anyString(), eq(HEAD), eq(BASE));
    }

    @Test
    void unreadableCandidateDoesNotPreventOtherFindingsFromBeingProcessed() {
        ReviewDocument.Finding unreadable = new ReviewDocument.Finding("bad", "Issue", "Missing.java", DiffSide.NEW, 2,
                "HIGH", "bug", QUOTE, List.of());
        when(api.getFileContent("o", "r", "Missing.java", HEAD)).thenThrow(new IllegalStateException("offline"));
        GroundingResult result = run(List.of(unreadable, finding("other", "HIGH", QUOTE)), empty(), List.of(), evidence(DIFF));
        assertThat(result.publish()).hasSize(1);
        assertThat(result.degraded()).isTrue();
        assertThat(result.dispositions()).singleElement().satisfies(d -> {
            assertThat(d.type()).isEqualTo(AuditEventType.FINDING_DROPPED);
            assertThat(d.reason()).isEqualTo("Pinned source unavailable");
        });
    }

    @Test
    void missingPriorFileWithdrawsButOtherFindingsStillPublish() {
        ReviewFindingRecord prior = stored();
        prior.setPath("Missing.java");
        when(api.getFileContent("o", "r", "Missing.java", HEAD)).thenThrow(
                org.springframework.web.client.HttpClientErrorException.create(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Not found", null, null, null));
        GroundingResult result = run(List.of(finding("other", "HIGH", QUOTE)), digest(prior, true), List.of(prior), evidence(DIFF));
        assertThat(result.publish()).hasSize(1);
        assertThat(result.threadActions()).singleElement().satisfies(a -> assertThat(a.reason()).startsWith("quote gone:"));
    }

    @Test
    void unavailablePriorIsNotSupersededAndOtherFindingsStillPublish() {
        ReviewFindingRecord prior = stored();
        prior.setPath("Missing.java");
        when(api.getFileContent("o", "r", "Missing.java", HEAD)).thenThrow(new IllegalStateException("offline"));
        GroundingResult result = run(List.of(finding("other", "HIGH", QUOTE)), digest(prior, true), List.of(prior), evidence(DIFF));
        assertThat(result.publish()).hasSize(1);
        assertThat(result.threadActions()).isEmpty();
        assertThat(result.supersedes()).isEmpty();
        assertThat(result.degraded()).isTrue();
    }

    @Test
    void reviewWideCitationsVerifiedAndStoredForRecheck() {
        ReviewDocument.Finding finding = new ReviewDocument.Finding("wide", "Cross-file issue", null, null, null,
                "HIGH", "bug", null, List.of(new ReviewDocument.Citation("A.java", QUOTE)));
        GroundingResult first = fresh(finding);
        assertThat(first.publish()).hasSize(1);
        ReviewFindingRecord prior = stored(first);
        GroundingResult second = run(List.of(), digest(prior, true), List.of(prior), evidence(DIFF));
        assertThat(second.threadActions()).singleElement().isInstanceOf(ThreadAction.UpheldReply.class);
    }

    @Test
    void reviewWideMissingOrInvalidCitationDropped() {
        ReviewDocument.Finding finding = new ReviewDocument.Finding("wide", "issue", null, null, null,
                "HIGH", "bug", null, List.of(new ReviewDocument.Citation("A.java", "fabricatedExpression();")));
        assertThat(fresh(finding).publish()).isEmpty();
    }

    @Test
    void leaseLostAbortsAndAudits() {
        ReviewRunLeaseService.LeaseToken lease = new ReviewRunLeaseService.LeaseToken(1L, "o", "r", 1L, "run", 1);
        assertThatThrownBy(() -> service.ground(new ReviewDocument("", List.of(), true), evidence(DIFF), empty(), List.of(),
                DoubleCheckSettings.defaults(), mock(AiClient.class), new Budget(10000, 3), event,
                location -> { throw new ReviewRunLeaseService.LeaseLostException(lease); }))
                .isInstanceOf(ReviewRunLeaseService.LeaseLostException.class);
        verify(audit).recordGroundingWarning(event, "GROUNDING", "lease lost", HEAD, BASE);
    }

    @Test
    void thrownDoubleCheckCallDegradesToBlockerOnly() {
        when(check.check(anyList(), any(), any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("offline"));
        GroundingResult result = fresh(finding("bug", "BLOCKER", QUOTE));
        assertThat(result.publish()).singleElement().satisfies(f -> assertThat(f.body()).contains("Not double-checked"));
        assertThat(result.degraded()).isTrue();
    }

    @Test
    void missingVerdictHeld() {
        when(check.check(anyList(), any(), any(), any(), any(), any(), any())).thenReturn(Map.of());
        GroundingResult result = fresh(finding("bug", "HIGH", QUOTE));
        assertThat(result.publish()).isEmpty();
        assertThat(result.dispositions()).singleElement().satisfies(d -> assertThat(d.type()).isEqualTo(AuditEventType.FINDING_HELD));
    }

    @Test
    void priorDropIsKnownBeforeNewSupersessionIsPlanned() {
        ReviewFindingRecord prior = stored();
        prior.setContextHash("old-context");
        verdict = c -> c.prior() ? Verdict.DROP : Verdict.KEEP;
        GroundingResult result = run(List.of(finding("bug", "HIGH", QUOTE)), digest(prior, true), List.of(prior), evidence(DIFF));
        assertThat(result.publish()).hasSize(1);
        assertThat(result.supersedes()).isEmpty();
        assertThat(result.threadActions()).singleElement().isInstanceOf(ThreadAction.Withdraw.class);
    }

    @Test
    void failedPriorIsNotSupersededByNewFinding() {
        ReviewFindingRecord prior = stored();
        prior.setContextHash("old-context");
        verdict = c -> c.prior() ? Verdict.FAILED : Verdict.KEEP;
        GroundingResult result = run(List.of(finding("bug", "HIGH", QUOTE)), digest(prior, true), List.of(prior), evidence(DIFF));
        assertThat(result.publish()).hasSize(1);
        assertThat(result.supersedes()).isEmpty();
        assertThat(result.threadActions()).isEmpty();
    }

    @Test
    void supersededDisputedPriorDoesNotAlsoGetUpheldReply() {
        ReviewFindingRecord prior = stored();
        prior.setContextHash("old-context");
        GroundingResult result = run(List.of(finding("bug", "HIGH", QUOTE)), digest(prior, true), List.of(prior), evidence(DIFF));
        assertThat(result.supersedes()).isNotEmpty();
        assertThat(result.threadActions()).isEmpty();
    }

    @Test
    void invalidSupportingCitationDropsAnchoredFinding() {
        ReviewDocument.Finding finding = new ReviewDocument.Finding("input", "Issue", "A.java", DiffSide.NEW, 2,
                "HIGH", "bug", QUOTE, List.of(new ReviewDocument.Citation("B.java", "fabricatedExpression();")));
        assertThat(fresh(finding).publish()).isEmpty();
    }

    @Test
    void reviewWideCitationRenameDoesNotRepostUnchangedIssue() {
        ReviewDocument.Finding old = new ReviewDocument.Finding("wide", "Issue", null, null, null, "HIGH", "bug", null,
                List.of(new ReviewDocument.Citation("Old.java", QUOTE)));
        ReviewFindingRecord prior = stored(fresh(old));
        ReviewDocument.Finding renamed = new ReviewDocument.Finding("wide", "Issue", null, null, null, "HIGH", "bug", null,
                List.of(new ReviewDocument.Citation("A.java", QUOTE)));
        GroundingResult result = run(List.of(renamed), digest(prior, false), List.of(prior),
                evidence("diff --git a/Old.java b/A.java\nrename from Old.java\nrename to A.java\n" + DIFF));
        assertThat(result.publish()).isEmpty();
        assertThat(result.threadActions()).isEmpty();
    }

    @Test
    void normalizedQuotesDedupEvenWhenIndentationDiffers() {
        GroundingResult result = run(List.of(finding("bug", "HIGH", QUOTE), finding("bug", "HIGH", "    " + QUOTE)),
                empty(), List.of(), evidence(DIFF));
        assertThat(result.publish()).hasSize(1);
    }

    @Test
    void unavailablePinnedNewEvidenceDropsRatherThanDiffFallback() {
        when(api.getFileContent(anyString(), anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("offline"));
        GroundingResult result = fresh(finding("bug", "BLOCKER", QUOTE));
        assertThat(result.publish()).isEmpty();
        assertThat(result.degraded()).isTrue();
        assertThat(candidates).isEmpty();
    }

    private GroundingResult fresh(ReviewDocument.Finding finding) { return run(List.of(finding), empty(), List.of(), evidence(DIFF)); }
    private GroundingResult run(List<ReviewDocument.Finding> findings, PriorFindingsDigest digest,
                                List<ReviewFindingRecord> priors, Evidence evidence) {
        return service.ground(new ReviewDocument("Summary", findings, true), evidence, digest, priors,
                DoubleCheckSettings.defaults(), mock(AiClient.class), new Budget(10000, 3), event, ReviewFence.NONE);
    }
    private Evidence evidence(String diff) {
        return new Evidence(new PinnedSourceReader(api, "o", "r", HEAD), new PinnedSourceReader(api, "o", "r", BASE), diff);
    }
    private ReviewDocument.Finding finding(String category, String severity, String quote) {
        return new ReviewDocument.Finding("input", "Issue", "A.java", DiffSide.NEW, 2, severity, category, quote, List.of());
    }
    private PriorFindingsDigest empty() { return new PriorFindingsDigest(List.of(), true, null); }
    private PriorFindingsDigest digest(ReviewFindingRecord prior, boolean disputed) {
        return new PriorFindingsDigest(List.of(new PriorFindingsDigest.PriorFinding(prior,
                disputed ? List.of(new PriorFindingsDigest.Reply("42", "human", "human claim", null)) : List.of(), disputed,
                disputed ? "42" : null)), true, null);
    }
    private ReviewFindingRecord stored() { return stored(fresh(finding("bug", "HIGH", QUOTE))); }
    private ReviewFindingRecord stored(GroundingResult result) {
        ReviewDocument.Finding finding = result.publish().getFirst();
        GroundingResult.FindingEvidence evidence = result.evidence().get(finding.id());
        ReviewFindingRecord record = new ReviewFindingRecord();
        record.setId(UUID.fromString(finding.id()));
        record.setStatus(ReviewFindingStatus.PUBLISHED);
        record.setPath(finding.path());
        record.setSide(finding.side() == null ? null : finding.side().name());
        record.setLine(finding.line());
        record.setQuote(evidence.storedQuote());
        record.setQuoteHash(evidence.quoteHash());
        record.setContextHash(evidence.contextHash());
        record.setCategory(finding.category());
        record.setSeverity(finding.severity());
        record.setBody(finding.body());
        return record;
    }
}
