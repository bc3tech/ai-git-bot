package org.remus.giteabot.review;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.repository.model.DiffSide;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
}
