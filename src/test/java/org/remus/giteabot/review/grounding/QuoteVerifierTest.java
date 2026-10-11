package org.remus.giteabot.review.grounding;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.review.ReviewDocument;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.model.DiffSide;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuoteVerifierTest {

    private static final String HEAD_SHA = "0123456789abcdef0123456789abcdef01234567";
    private static final String BASE_SHA = "abcdef0123456789abcdef0123456789abcdef01";
    private final RepositoryApiClient api = mock(RepositoryApiClient.class);
    private final QuoteVerifier verifier = new QuoteVerifier();

    @Test
    void matchesVerbatimQuoteAtAnchorAndCachesPinnedFileReads() {
        String content = "first line\n    validatePermission(subject, resource);\nlast line\n";
        when(api.getFileContent("owner", "repo", "src/Auth.java", HEAD_SHA)).thenReturn(content);
        PinnedSourceReader head = reader(HEAD_SHA);
        ReviewDocument.Finding finding = finding("src/Auth.java", DiffSide.NEW, 2,
                "validatePermission(subject, resource);");

        QuoteMatch first = verifier.verify(finding, head, reader(BASE_SHA));
        QuoteMatch second = verifier.verify(finding, head, reader(BASE_SHA));

        assertThat(first.matched()).isTrue();
        assertThat(first.matchedSpan()).isEqualTo(new QuoteMatch.MatchedSpan(2, 2));
        assertThat(first.contextHash()).hasSize(64);
        assertThat(second).isEqualTo(first);
        verify(api, times(1)).getFileContent("owner", "repo", "src/Auth.java", HEAD_SHA);
    }

    @Test
    void matchesWithDifferentLeadingIndentation() {
        when(api.getFileContent("owner", "repo", "A.java", HEAD_SHA))
                .thenReturn("  checkAccess(principal, target);\n");

        QuoteMatch result = verifier.verify(finding("A.java", DiffSide.NEW, 1,
                "        checkAccess(principal, target);"), reader(HEAD_SHA), reader(BASE_SHA));

        assertThat(result.matched()).isTrue();
    }

    @Test
    void keepsInternalWhitespaceLiteral() {
        when(api.getFileContent("owner", "repo", "A.java", HEAD_SHA)).thenReturn("String value = \"a b\";\n");

        QuoteMatch result = verifier.verify(finding("A.java", DiffSide.NEW, 1,
                "String value = \"a  b\";"), reader(HEAD_SHA), reader(BASE_SHA));

        assertFailure(result, QuoteMatch.FailureReason.QUOTE_NOT_FOUND);
    }

    @Test
    void matchesMultilineQuoteContainingAnchor() {
        when(api.getFileContent("owner", "repo", "A.java", HEAD_SHA))
                .thenReturn("if (hasAccess(user)) {\n    enforcePolicy(user, action);\n}\n");

        QuoteMatch result = verifier.verify(finding("A.java", DiffSide.NEW, 2,
                "if (hasAccess(user)) {\n    enforcePolicy(user, action);\n}"),
                reader(HEAD_SHA), reader(BASE_SHA));

        assertThat(result.matched()).isTrue();
        assertThat(result.matchedSpan()).isEqualTo(new QuoteMatch.MatchedSpan(1, 3));
    }

    @Test
    void rejectsAbsentFabricatedQuote() {
        when(api.getFileContent("owner", "repo", "release.sh", HEAD_SHA)).thenReturn("release --force\n");

        QuoteMatch result = verifier.verify(finding("release.sh", DiffSide.NEW, 1,
                "force_args=($RELEASE_FORCE)"), reader(HEAD_SHA), reader(BASE_SHA));

        assertFailure(result, QuoteMatch.FailureReason.QUOTE_NOT_FOUND);
    }

    @Test
    void rejectsQuoteOutsideTightAnchorWindow() {
        when(api.getFileContent("owner", "repo", "A.java", HEAD_SHA))
                .thenReturn(String.join("\n", lines(20, "authorizeRequest(user, action);")));

        QuoteMatch result = verifier.verify(finding("A.java", DiffSide.NEW, 14,
                "authorizeRequest(user, action);"), reader(HEAD_SHA), reader(BASE_SHA));

        assertFailure(result, QuoteMatch.FailureReason.QUOTE_OUT_OF_WINDOW);
    }

    @Test
    void rejectsAmbiguousOccurrencesNearAnchor() {
        when(api.getFileContent("owner", "repo", "A.java", HEAD_SHA))
                .thenReturn("checkAccess(principal, target);\nother();\ncheckAccess(principal, target);\n");

        QuoteMatch result = verifier.verify(finding("A.java", DiffSide.NEW, 1,
                "checkAccess(principal, target);"), reader(HEAD_SHA), reader(BASE_SHA));

        assertFailure(result, QuoteMatch.FailureReason.QUOTE_AMBIGUOUS);
    }

    @Test
    void rejectsMissingAndTooWeakQuotes() {
        ReviewDocument.Finding missing = finding("A.java", DiffSide.NEW, 1, " \n ");
        QuoteMatch missingResult = verifier.verify(missing, reader(HEAD_SHA), reader(BASE_SHA));
        when(api.getFileContent("owner", "repo", "A.java", HEAD_SHA)).thenReturn("}\n");
        QuoteMatch weakResult = verifier.verify(finding("A.java", DiffSide.NEW, 1, "}"),
                reader(HEAD_SHA), reader(BASE_SHA));

        assertFailure(missingResult, QuoteMatch.FailureReason.QUOTE_MISSING);
        assertFailure(weakResult, QuoteMatch.FailureReason.QUOTE_TOO_WEAK);
    }

    @Test
    void resolvesLeftFindingOnlyAgainstEffectiveBaseSha() {
        when(api.getFileContent("owner", "repo", "A.java", BASE_SHA)).thenReturn("removedGuard(user, key);\n");

        QuoteMatch result = verifier.verify(finding("A.java", DiffSide.OLD, 1,
                "removedGuard(user, key);"), reader(HEAD_SHA), reader(BASE_SHA));

        assertThat(result.matched()).isTrue();
        verify(api).getFileContent("owner", "repo", "A.java", BASE_SHA);
    }

    @Test
    void permitsUniquePriorRelocationButRejectsDuplicateRelocations() {
        String shifted = String.join("\n", lines(50, "authorizeRequest(user, action);"));
        when(api.getFileContent("owner", "repo", "A.java", HEAD_SHA)).thenReturn(shifted);
        ReviewDocument.Finding prior = finding("A.java", DiffSide.NEW, 1,
                "authorizeRequest(user, action);");

        QuoteMatch relocated = verifier.verifyPrior(prior, reader(HEAD_SHA), reader(BASE_SHA));
        when(api.getFileContent("owner", "repo", "B.java", HEAD_SHA)).thenReturn(
                "authorizeRequest(user, action);\n" + shifted);
        QuoteMatch ambiguous = verifier.verifyPrior(finding("B.java", DiffSide.NEW, 100,
                "authorizeRequest(user, action);"), reader(HEAD_SHA), reader(BASE_SHA));

        assertThat(relocated.matched()).isTrue();
        assertThat(relocated.matchedSpan().startLine()).isEqualTo(50);
        assertFailure(ambiguous, QuoteMatch.FailureReason.QUOTE_AMBIGUOUS);
    }

    @Test
    void verifiesOnlyDiffLinesInUnpinnedModeAndDoesNotBindToAnchor() {
        String diff = """
                diff --git a/A.java b/A.java
                index 1111111..2222222 100644
                --- a/A.java
                +++ b/A.java
                @@ -10,2 +10,3 @@
                 existingMethod(user, resource);
                +authorizeRequest(user, action);
                 return result;
                """;
        DiffTextSource source = new DiffTextSource(diff);
        ReviewDocument.Finding added = finding("A.java", DiffSide.NEW, 200,
                "authorizeRequest(user, action);");
        ReviewDocument.Finding absent = finding("A.java", DiffSide.NEW, 200,
                "shouldNotExist(user, resource);");

        assertThat(verifier.verifyUnpinned(added, source).matched()).isTrue();
        assertFailure(verifier.verifyUnpinned(absent, source), QuoteMatch.FailureReason.QUOTE_NOT_FOUND);
        assertThat(source.lines("A.java", DiffSide.OLD)).extracting(DiffTextSource.DiffLine::content)
                .containsExactly("existingMethod(user, resource);", "return result;");
    }

    @Test
    void requiresCitationsToBeUniqueInTheirFile() {
        when(api.getFileContent("owner", "repo", "A.java", HEAD_SHA))
                .thenReturn("validatePermission(subject, resource);\nvalidatePermission(subject, resource);\n");

        QuoteMatch result = verifier.verifyCitation(
                new ReviewDocument.Citation("A.java", "validatePermission(subject, resource);"), reader(HEAD_SHA));

        assertFailure(result, QuoteMatch.FailureReason.QUOTE_AMBIGUOUS);
    }

    @Test
    void refusesBranchNamesAndWrapsUnreadableRevision() {
        assertThatThrownBy(() -> reader("main"))
                .isInstanceOf(IllegalArgumentException.class);
        when(api.getFileContent("owner", "repo", "missing.java", HEAD_SHA))
                .thenThrow(new IllegalStateException("not found at revision"));

        assertThatThrownBy(() -> reader(HEAD_SHA).read("missing.java"))
                .isInstanceOf(PinnedSourceReader.RevisionUnavailableException.class);
    }

    @Test
    void verifiesUnpinnedCitationAgainstRemovedDiffTextWhenItIsAbsentFromHeadSide() {
        String diff = """
                diff --git a/A.java b/A.java
                --- a/A.java
                +++ b/A.java
                @@ -7,1 +6,0 @@
                -validatePermission(subject, resource);
                """;

        QuoteMatch result = verifier.verifyCitationUnpinned(
                new ReviewDocument.Citation("A.java", "validatePermission(subject, resource);"),
                new DiffTextSource(diff));

        assertThat(result.matched()).isTrue();
    }
    private PinnedSourceReader reader(String sha) {
        return new PinnedSourceReader(api, "owner", "repo", sha);
    }

    private static ReviewDocument.Finding finding(String path, DiffSide side, int line, String quote) {
        return new ReviewDocument.Finding("finding", "A finding body", path, side, line,
                "MEDIUM", "bug", quote, List.of());
    }

    private static List<String> lines(int count, String target) {
        List<String> result = new ArrayList<>(count);
        for (int line = 1; line <= count; line++) {
            result.add(line == count ? target : "ordinaryLine();");
        }
        return result;
    }

    private static void assertFailure(QuoteMatch result, QuoteMatch.FailureReason reason) {
        assertThat(result.matched()).isFalse();
        assertThat(result.failureReason()).isEqualTo(reason);
    }
}
