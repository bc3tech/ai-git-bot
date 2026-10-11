package org.remus.giteabot.review;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.repository.model.DiffSide;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewOutputParserTest {

    @Test
    void parsesFencedEnvelopeWithLocatedAndReviewWideFindings() {
        String raw = """
                Here is my review.

                ```json
                {
                  "summary": "Looks mostly good.",
                  "findings": [
                    {"path": "src/A.java", "side": "new", "line": 12, "severity": "MEDIUM", "category": "bug",
                     "body": "Use `Objects.equals` here: {\\"a\\": 1} — café ✓"},
                    {"body": "Consider adding tests.", "severity": "LOW"}
                  ],
                  "unknown": true
                }
                ```
                """;
        ReviewOutputParser.Result result = ReviewOutputParser.parse(raw);

        assertThat(result.warning()).isNull();
        ReviewDocument doc = result.document();
        assertThat(doc.structured()).isTrue();
        assertThat(doc.summary()).isEqualTo("Looks mostly good.");
        assertThat(doc.findings()).hasSize(2);
        ReviewDocument.Finding located = doc.findings().getFirst();
        assertThat(located.hasLocation()).isTrue();
        assertThat(located.path()).isEqualTo("src/A.java");
        assertThat(located.side()).isEqualTo(DiffSide.NEW);
        assertThat(located.line()).isEqualTo(12);
        assertThat(located.body()).isEqualTo("Use `Objects.equals` here: {\"a\": 1} — café ✓");
        assertThat(located.severity()).isEqualTo("MEDIUM");
        assertThat(located.quote()).isNull();
        assertThat(located.citations()).isEmpty();
        assertThat(doc.findings().get(1).hasLocation()).isFalse();
    }

    @Test
    void parsesBareEnvelopeAndToleratesTrailingClassificationLine() {
        String raw = """
                {"summary": "Two issues.", "findings": [{"path": "x.py", "side": "old", "line": 3, "body": "Removed guard."}]}
                {"blocker": 0, "medium": 1, "low": 0}
                """;
        ReviewDocument doc = ReviewOutputParser.parse(raw).document();
        assertThat(doc.structured()).isTrue();
        assertThat(doc.summary()).isEqualTo("Two issues.");
        assertThat(doc.findings()).singleElement().satisfies(f -> {
            assertThat(f.side()).isEqualTo(DiffSide.OLD);
            assertThat(f.line()).isEqualTo(3);
        });
    }

    @Test
    void nestedClassificationPreservesBareAndFencedReviewEvidence() {
        String envelope = """
                {"summary": "One issue.", "findings": [
                  {"path": "src/A.java", "side": "new", "line": 12, "severity": "MEDIUM",
                   "quote": "return value.toString();", "body": "Value may be null."},
                  {"body": "Check the contract.", "citations": [
                    {"path": "src/B.java", "quote": "return null;"}
                  ]}
                ], "classification": {"blocker": 0, "medium": 1, "low": 0}}
                """;
        for (String raw : List.of(envelope, "```json\n" + envelope + "```")) {
            ReviewOutputParser.Result result = ReviewOutputParser.parse(raw);
            assertThat(result.warning()).isNull();
            assertThat(result.document().structured()).isTrue();
            assertThat(result.document().summary()).isEqualTo("One issue.");
            assertThat(result.document().findings()).hasSize(2);
            assertThat(result.document().findings().getFirst()).satisfies(f -> {
                assertThat(f.path()).isEqualTo("src/A.java");
                assertThat(f.side()).isEqualTo(DiffSide.NEW);
                assertThat(f.line()).isEqualTo(12);
                assertThat(f.severity()).isEqualTo("MEDIUM");
                assertThat(f.quote()).isEqualTo("return value.toString();");
                assertThat(f.body()).isEqualTo("Value may be null.");
            });
            assertThat(result.document().findings().get(1).citations()).containsExactly(
                    new ReviewDocument.Citation("src/B.java", "return null;"));
        }
    }

    @Test
    void legacyInstructionExamplesParseWithOptionalNestedClassification() {
        for (boolean formalDecision : List.of(false, true)) {
            String instructions = ReviewOutputInstructions.agenticLegacy(formalDecision);
            String example = instructions.split("Completion example:\\n", 2)[1].split("\\n\\n", 2)[0];
            ReviewOutputParser.Result result = ReviewOutputParser.parse(example);
            assertThat(result.warning()).isNull();
            assertThat(result.document().structured()).isTrue();
            assertThat(result.document().summary()).startsWith("Concise overall assessment");
            assertThat(result.document().findings()).singleElement().satisfies(f -> {
                assertThat(f.path()).isEqualTo("src/main/java/Foo.java");
                assertThat(f.quote()).isEqualTo("return value.toString();");
                assertThat(f.body()).startsWith("Markdown explanation");
            });
            assertThat(instructions).doesNotContain("```", "must come after the block");
        }
    }

    @Test
    void standardAndHistoricalAgenticInstructionsKeepFencedContracts() {
        String standard = ReviewOutputInstructions.standard();
        assertThat(ReviewOutputInstructions.agentic(false)).isEqualTo(standard);
        assertThat(standard).contains(
                "Write your final review as exactly one JSON object inside a ```json fenced code block:",
                "Do not write anything outside the fenced JSON block.");
        String example = standard.split("```json\\n", 2)[1].split("\\n```", 2)[0];
        assertThat(ReviewOutputParser.parse(example).document().findings()).hasSize(1);
        assertThat(ReviewOutputInstructions.agentic(true)).contains(
                "classification line, which must come after the block as instructed.");
        assertThat(standard).doesNotContain("\"classification\"");
    }

    @Test
    void invalidLocationsKeepTheFindingAsSummaryOnly() {
        String raw = """
                ```json
                {"summary": "s", "findings": [
                  {"path": "a.txt", "line": 4, "body": "missing side"},
                  {"path": "a.txt", "side": "new", "line": "4", "body": "string line"},
                  {"path": "a.txt", "side": "sideways", "line": 4, "body": "bad side"},
                  {"path": "a.txt", "side": "new", "line": 4.5, "body": "fraction"},
                  {"path": "a.txt", "side": "new", "line": 4}
                ]}
                ```
                """;
        ReviewOutputParser.Result result = ReviewOutputParser.parse(raw);
        assertThat(result.document().findings()).hasSize(4)
                .allSatisfy(f -> assertThat(f.hasLocation()).isFalse());
        assertThat(result.warning()).contains("1 structured finding");
    }

    @Test
    void titleIsKeptWithBody() {
        String raw = "{\"findings\": [{\"title\": \"Null deref\", \"body\": \"x may be null\"}]}";
        assertThat(ReviewOutputParser.parse(raw).document().findings().getFirst().body())
                .isEqualTo("**Null deref**\n\nx may be null");
    }

    @Test
    void legacyMarkdownFallsBackToTextWithoutWarning() {
        String raw = "## Review\n\n[Comment on src/A.java]: something is off { not json }";
        ReviewOutputParser.Result result = ReviewOutputParser.parse(raw);
        assertThat(result.warning()).isNull();
        assertThat(result.document().structured()).isFalse();
        assertThat(result.document().summary()).isEqualTo(raw);
        assertThat(result.document().findings()).isEmpty();
    }

    @Test
    void malformedEnvelopeFallsBackToOriginalTextWithWarning() {
        String raw = "```json\n{\"summary\": \"oops\", \"findings\": [ {\"body\": \"x\" ]\n```";
        ReviewOutputParser.Result result = ReviewOutputParser.parse(raw);
        assertThat(result.warning()).isNotNull();
        assertThat(result.document().structured()).isFalse();
        assertThat(result.document().summary()).isEqualTo(raw);
    }

    @Test
    void nonArrayFindingsFallsBackToOriginalTextWithWarning() {
        String raw = "{\"summary\": \"\", \"findings\": \"critical issue in Foo.java\"}";
        ReviewOutputParser.Result result = ReviewOutputParser.parse(raw);
        assertThat(result.warning()).isNotNull();
        assertThat(result.document().structured()).isFalse();
        assertThat(result.document().summary()).isEqualTo(raw);
    }

    @Test
    void classificationBlockAloneIsNotAnEnvelope() {
        String raw = "Fine.\n{\"blocker\": 0, \"medium\": 0, \"low\": 1, \"findings\": [{\"severity\": \"low\"}]}";
        assertThat(ReviewOutputParser.parse(raw).document().structured()).isFalse();
    }

    @Test
    void emptyFindingsWithSummaryIsAValidReview() {
        ReviewDocument doc = ReviewOutputParser.parse("{\"summary\": \"No issues.\", \"findings\": []}").document();
        assertThat(doc.structured()).isTrue();
        assertThat(doc.summary()).isEqualTo("No issues.");
        assertThat(doc.findings()).isEmpty();
    }

    @Test
    void mergeConcatenatesSummariesAndCollapsesExactDuplicates() {
        ReviewDocument.Finding a = new ReviewDocument.Finding("f1", "same", "a", DiffSide.NEW, 1, null, null);
        ReviewDocument.Finding b = new ReviewDocument.Finding("f2", "other", "a", DiffSide.NEW, 2, null, null);
        ReviewDocument merged = ReviewOutputParser.merge(List.of(
                new ReviewDocument("one", List.of(a), true),
                new ReviewDocument("two", List.of(a, b), true)));
        assertThat(merged.summary()).isEqualTo("one\n\ntwo");
        assertThat(merged.findings()).extracting(ReviewDocument.Finding::body).containsExactly("same", "other");
        assertThat(merged.findings()).extracting(ReviewDocument.Finding::id).containsExactly("f1", "f2");
    }

    @Test
    void parsesAnchoredQuoteWithoutChangingWhitespace() {
        String raw = """
                {"findings": [{"path": "src/A.java", "side": "new", "line": 12,
                  "body": "Null dereference.", "quote": "  return value.toString();  "}]}
                """;
        ReviewOutputParser.Result result = ReviewOutputParser.parse(raw);
        assertThat(result.warning()).isNull();
        assertThat(result.document().findings()).singleElement().satisfies(f -> {
            assertThat(f.hasLocation()).isTrue();
            assertThat(f.quote()).isEqualTo("  return value.toString();  ");
            assertThat(f.citations()).isEmpty();
        });
    }

    @Test
    void parsesReviewWideFindingWithTwoCitations() {
        String raw = """
                {"findings": [{"body": "The contracts disagree.", "citations": [
                  {"path": "src/A.java", "quote": "return null;"},
                  {"path": "src/B.java", "quote": "value.toString();"}
                ]}]}
                """;
        ReviewOutputParser.Result result = ReviewOutputParser.parse(raw);
        assertThat(result.warning()).isNull();
        assertThat(result.document().findings()).singleElement().satisfies(f -> {
            assertThat(f.hasLocation()).isFalse();
            assertThat(f.quote()).isNull();
            assertThat(f.citations()).containsExactly(
                    new ReviewDocument.Citation("src/A.java", "return null;"),
                    new ReviewDocument.Citation("src/B.java", "value.toString();"));
        });
    }

    @Test
    void preservesMultilineQuoteAndCitationNewlines() {
        String raw = """
                {"findings": [
                  {"path": "src/A.java", "side": "old", "line": 12, "body": "Missing guard.",
                   "quote": "  if (value == null) {\\n    return null;\\n  }"},
                  {"body": "Contract issue.", "citations": [
                    {"path": "src/B.java", "quote": "return value\\n    .toString();"}
                  ]}
                ]}
                """;
        ReviewDocument doc = ReviewOutputParser.parse(raw).document();
        assertThat(doc.structured()).isTrue();
        assertThat(doc.findings().getFirst().quote()).isEqualTo("  if (value == null) {\n    return null;\n  }");
        assertThat(doc.findings().get(1).citations().getFirst().quote()).isEqualTo("return value\n    .toString();");
    }

    @Test
    void leavesInvalidEvidenceForLaterValidation() {
        String raw = """
                {"findings": [
                  {"body": "Missing evidence."},
                  {"body": "Wrong types.", "quote": 42, "citations": {}},
                  {"body": "Incomplete citations.", "quote": "", "citations": [
                    {"path": "src/A.java"}, {"quote": "return null;"}, null
                  ]}
                ]}
                """;
        ReviewOutputParser.Result result = ReviewOutputParser.parse(raw);
        assertThat(result.warning()).isNull();
        assertThat(result.document().structured()).isTrue();
        assertThat(result.document().findings()).hasSize(3);
        assertThat(result.document().findings().getFirst().quote()).isNull();
        assertThat(result.document().findings().getFirst().citations()).isEmpty();
        assertThat(result.document().findings().get(1).quote()).isNull();
        assertThat(result.document().findings().get(1).citations()).isEmpty();
        assertThat(result.document().findings().get(2).quote()).isEmpty();
        assertThat(result.document().findings().get(2).citations()).containsExactly(
                new ReviewDocument.Citation("src/A.java", null),
                new ReviewDocument.Citation(null, "return null;"),
                new ReviewDocument.Citation(null, null));
    }

    @Test
    void mergePreservesEvidenceAndDistinctCategories() {
        ReviewDocument.Finding a = new ReviewDocument.Finding("chunk-1", "same", "a", DiffSide.NEW, 1,
                "MEDIUM", "bug", "return null;", null);
        ReviewDocument.Finding duplicate = new ReviewDocument.Finding("chunk-2", "same", "a", DiffSide.NEW, 1,
                "MEDIUM", "bug", "return null;", null);
        ReviewDocument.Finding otherCategory = new ReviewDocument.Finding("chunk-2", "same", "a", DiffSide.NEW, 1,
                "MEDIUM", "security", "return null;", null);
        ReviewDocument.Finding otherQuote = new ReviewDocument.Finding("chunk-3", "same", "a", DiffSide.NEW, 1,
                "MEDIUM", "bug", "return value;", null);
        List<ReviewDocument.Citation> citations = List.of(new ReviewDocument.Citation("a", "return null;"));
        ReviewDocument.Finding wide = new ReviewDocument.Finding("chunk-1", "contracts", null, null, null,
                "LOW", "bug", null, citations);
        ReviewDocument.Finding otherCitations = new ReviewDocument.Finding("chunk-2", "contracts", null, null, null,
                "LOW", "bug", null, List.of(new ReviewDocument.Citation("b", "return value;")));
        ReviewDocument merged = ReviewOutputParser.merge(List.of(
                new ReviewDocument("one", List.of(a, wide), true),
                new ReviewDocument("two", List.of(duplicate, otherCategory, otherQuote, otherCitations), true)));
        assertThat(merged.structured()).isTrue();
        assertThat(merged.findings()).containsExactly(
                new ReviewDocument.Finding("f1", "same", "a", DiffSide.NEW, 1,
                        "MEDIUM", "bug", "return null;", List.of()),
                new ReviewDocument.Finding("f2", "contracts", null, null, null,
                        "LOW", "bug", null, citations),
                new ReviewDocument.Finding("f3", "same", "a", DiffSide.NEW, 1,
                        "MEDIUM", "security", "return null;", List.of()),
                new ReviewDocument.Finding("f4", "same", "a", DiffSide.NEW, 1,
                        "MEDIUM", "bug", "return value;", List.of()),
                new ReviewDocument.Finding("f5", "contracts", null, null, null,
                        "LOW", "bug", null, otherCitations.citations()));
    }

    @Test
    void findingCopiesCitationsAndDefaultsMissingEvidence() {
        List<ReviewDocument.Citation> citations = new ArrayList<>();
        citations.add(new ReviewDocument.Citation("a", "return null;"));
        ReviewDocument.Finding finding = new ReviewDocument.Finding("f1", "issue", null, null, null,
                null, null, null, citations);
        citations.clear();
        assertThat(finding.citations()).containsExactly(new ReviewDocument.Citation("a", "return null;"));
        assertThatThrownBy(() -> finding.citations().clear()).isInstanceOf(UnsupportedOperationException.class);
        ReviewDocument.Finding legacy = new ReviewDocument.Finding("f2", "issue", null, null, null, null, null);
        assertThat(legacy.quote()).isNull();
        assertThat(legacy.citations()).isEmpty();
    }

    @Test
    void standardAndAgenticInstructionsRequireVerbatimEvidence() {
        for (String instructions : List.of(ReviewOutputInstructions.standard(),
                ReviewOutputInstructions.agentic(false), ReviewOutputInstructions.agentic(true))) {
            assertThat(instructions).contains(
                    "Every line-anchored finding must include \"quote\"",
                    "exact, verbatim quote of the current",
                    "include \"citations\": at least one object with \"path\" and \"quote\"",
                    "escape newlines as \\n in JSON");
        }
    }
}
