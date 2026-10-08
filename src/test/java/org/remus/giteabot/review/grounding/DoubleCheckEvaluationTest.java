package org.remus.giteabot.review.grounding;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.remus.giteabot.review.grounding.DoubleCheckService.*;

class DoubleCheckEvaluationTest {
    @Test
    void labeledCasesHaveGroundedEvidenceAndMeaningfulCategories() throws Exception {
        List<DoubleCheckEvaluation.Case> cases = DoubleCheckEvaluation.cases();
        assertThat(cases).hasSize(12);
        assertThat(cases).extracting(DoubleCheckEvaluation.Case::id).doesNotHaveDuplicates();
        assertThat(cases).allSatisfy(testCase -> {
            assertThat(testCase.evidence().pinned()).isTrue();
            assertThat(new QuoteVerifier().verify(testCase.candidate().finding(), testCase.evidence().head(),
                    testCase.evidence().effectiveBase()).matched()).isTrue();
            assertThat(testCase.candidate().finding().body()).doesNotContain("Synthetic valid finding control");
        });
        assertThat(cases.stream().filter(c -> !c.disputed() && c.expected() == Verdict.DROP)).hasSize(4);
        assertThat(cases.stream().filter(c -> !c.disputed() && c.expected() == Verdict.KEEP)).hasSize(5);
        assertThat(cases.stream().filter(DoubleCheckEvaluation.Case::disputed)).hasSize(3);
    }

    @Test
    void perfectResultsPassAndReportAllMetrics() throws Exception {
        List<DoubleCheckEvaluation.Result> results = results(DoubleCheckEvaluation.Case::expected);
        DoubleCheckEvaluation.Metrics metrics = DoubleCheckEvaluation.metrics(results);
        assertThat(metrics.accepted()).isTrue();
        assertThat(metrics.rejection()).isEqualTo(1);
        assertThat(metrics.retention()).isEqualTo(1);
        assertThat(metrics.disputeAccuracy()).isEqualTo(1);
        assertThat(metrics.agreement()).isEqualTo(1);
        assertThat(DoubleCheckEvaluation.report(results)).contains("100.00%", "Dispute accuracy",
                "Run-to-run unanimous agreement", "0 / 0 / 0").doesNotContain("Thresholds missed");
    }

    @Test
    void uncertainFailedAndSkippedNeverCountAsCorrectRejectionsOrRetention() throws Exception {
        for (Verdict verdict : List.of(Verdict.UNCERTAIN, Verdict.FAILED, Verdict.SKIPPED)) {
            List<DoubleCheckEvaluation.Result> results = results(testCase -> verdict);
            DoubleCheckEvaluation.Metrics metrics = DoubleCheckEvaluation.metrics(results);
            assertThat(metrics.accepted()).isFalse();
            assertThat(metrics.rejection()).isZero();
            assertThat(metrics.retention()).isZero();
            assertThat(metrics.disputeAccuracy()).isZero();
            assertThat(metrics.uncertain() + metrics.failed() + metrics.skipped()).isEqualTo(36);
            assertThat(DoubleCheckEvaluation.report(results)).contains("Thresholds missed", "DOUBLE_CHECK_AI_INTEGRATION_ID");
        }
    }

    @Test
    void disagreementAndWrongDisputeAreReportedSeparatelyFromNewFindings() throws Exception {
        List<DoubleCheckEvaluation.Result> results = results(DoubleCheckEvaluation.Case::expected);
        DoubleCheckEvaluation.Result correct = results.getLast();
        results.set(results.size() - 1, new DoubleCheckEvaluation.Result(correct.testCase(), correct.repetition(),
                new DoubleCheckOutcome(Verdict.KEEP, "incorrect dispute verdict")));
        DoubleCheckEvaluation.Metrics metrics = DoubleCheckEvaluation.metrics(results);
        assertThat(metrics.accepted()).isTrue();
        assertThat(metrics.disputeAccuracy()).isEqualTo(8.0 / 9);
        assertThat(metrics.agreement()).isEqualTo(11.0 / 12);
    }

    @Test
    void incompleteOrRepeatedRepetitionsCannotProduceMetrics() throws Exception {
        List<DoubleCheckEvaluation.Result> results = results(DoubleCheckEvaluation.Case::expected);
        List<DoubleCheckEvaluation.Result> incomplete = new ArrayList<>(results);
        incomplete.removeLast();
        assertThatThrownBy(() -> DoubleCheckEvaluation.metrics(incomplete)).isInstanceOf(IllegalArgumentException.class);
        results.set(results.size() - 1, results.get(results.size() - 2));
        assertThatThrownBy(() -> DoubleCheckEvaluation.metrics(results)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DoubleCheckEvaluation.metrics(List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void thresholdsIncludeTheirBoundaries() {
        assertThat(new DoubleCheckEvaluation.Metrics(0.90, 0.95, 1, 1, 0, 0, 0).accepted()).isTrue();
        assertThat(new DoubleCheckEvaluation.Metrics(0.89, 1, 1, 1, 0, 0, 0).accepted()).isFalse();
        assertThat(new DoubleCheckEvaluation.Metrics(1, 0.94, 1, 1, 0, 0, 0).accepted()).isFalse();
    }

    private static List<DoubleCheckEvaluation.Result> results(Function<DoubleCheckEvaluation.Case, Verdict> verdict)
            throws Exception {
        List<DoubleCheckEvaluation.Result> results = new ArrayList<>();
        for (DoubleCheckEvaluation.Case testCase : DoubleCheckEvaluation.cases()) {
            for (int repetition = 1; repetition <= DoubleCheckEvaluation.REPETITIONS; repetition++) {
                results.add(new DoubleCheckEvaluation.Result(testCase, repetition,
                        new DoubleCheckOutcome(verdict.apply(testCase), "evaluation test")));
            }
        }
        return results;
    }
}
