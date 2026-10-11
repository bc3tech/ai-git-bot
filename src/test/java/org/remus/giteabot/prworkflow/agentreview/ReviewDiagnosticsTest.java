package org.remus.giteabot.prworkflow.agentreview;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.agent.model.ImplementationPlan;
import org.remus.giteabot.agent.shared.AgentJackson;
import org.remus.giteabot.ai.ToolCall;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewDiagnosticsTest {

    @Test
    void nativeRequestsIgnoreCallIdsAndObjectKeyOrderButCountRepeatedOccurrencesAndRounds() throws Exception {
        var collector = collector();
        var first = new ToolCall("call-a", "cat",
                AgentJackson.mapper().readTree("""
                        {"path":"src/private.java","options":{"b":2,"a":1}}
                        """));
        var second = new ToolCall("call-b", "cat",
                AgentJackson.mapper().readTree("""
                        {"options":{"a":1,"b":2},"path":"src/private.java"}
                        """));
        var third = new ToolCall("call-c", "cat",
                AgentJackson.mapper().readTree("""
                        {"path":"src/private.java","options":{"a":1,"b":2}}
                        """));

        collector.observeNativeRequests(List.of(first, second, third), "refs/heads/main", 4);
        collector.observeNativeRequests(List.of(second), "refs/heads/main", 5);

        var snapshot = collector.snapshot(ReviewDiagnostics.Category.CONTEXT_ROUND_LIMIT);
        String serialized = AgentJackson.mapper().writeValueAsString(snapshot);
        assertThat(snapshot.requestOccurrences()).isEqualTo(4);
        assertThat(snapshot.repeatedRequestOccurrences()).isEqualTo(3);
        assertThat(snapshot.repeatedRequestRounds()).isEqualTo(2);
        assertThat(snapshot.details()).doesNotContain("private.java", "options", "call-a");
        assertThat(serialized).doesNotContain("private.java", "options", "call-a");
    }

    @Test
    void changedArgumentsArrayOrderAndResolvedRefAreNotIdenticalRequests() throws Exception {
        var collector = collector();
        var base = new ToolCall("id-1", "search",
                AgentJackson.mapper().readTree("""
                        {"args":["needle","src"]}
                        """));
        var changedArg = new ToolCall("id-2", "search",
                AgentJackson.mapper().readTree("""
                        {"args":["other","src"]}
                        """));
        var changedOrder = new ToolCall("id-3", "search",
                AgentJackson.mapper().readTree("""
                        {"args":["src","needle"]}
                        """));

        collector.observeNativeRequests(List.of(base, changedArg, changedOrder, base),
                "refs/heads/main", 1);
        collector.observeNativeRequests(List.of(base), "refs/heads/other", 2);

        var snapshot = collector.snapshot(ReviewDiagnostics.Category.CONTEXT_ROUND_LIMIT);
        assertThat(snapshot.requestOccurrences()).isEqualTo(5);
        assertThat(snapshot.repeatedRequestOccurrences()).isEqualTo(1);
        assertThat(snapshot.repeatedRequestRounds()).isEqualTo(1);
    }

    @Test
    void repeatStateIsPerCollectorAndMissingUsageRemainsUnavailable() {
        var first = collector();
        var second = collector();
        var observation = new org.remus.giteabot.agent.loop.AgentRunContext.RoundObservation(
                1, org.remus.giteabot.agent.loop.ToolingMode.LEGACY,
                org.remus.giteabot.ai.StopReason.END_TURN, null, 4096, 1200, 10);
        first.observeTurn(observation);
        first.observeLegacyRequests(List.of(), List.of("private.java"), "main", 1);
        second.observeLegacyRequests(List.of(), List.of("private.java"), "main", 2);

        var snapshot = first.snapshot(ReviewDiagnostics.Category.OUTPUT_TOKEN_LIMIT);
        assertThat(snapshot.outputTokens()).isNull();
        assertThat(snapshot.totalTurns()).isEqualTo(1);
        assertThat(second.snapshot(ReviewDiagnostics.Category.CONTEXT_ROUND_LIMIT)
                .repeatedRequestOccurrences()).isZero();
        assertThat(snapshot.details()).doesNotContain("private.java");
    }

    @Test
    void legacyRequestsIgnoreGeneratedIdsButPreserveArgumentTextAndOrder() {
        var collector = collector();
        ImplementationPlan.ToolRequest first = request("call-one", List.of("cat", "src/secret.java"));
        ImplementationPlan.ToolRequest same = request("call-two", List.of("cat", "src/secret.java"));
        ImplementationPlan.ToolRequest reordered = request("call-three", List.of("src/secret.java", "cat"));

        collector.observeLegacyRequests(List.of(first, same, reordered), null, "refs/heads/main", 3);

        var snapshot = collector.snapshot(ReviewDiagnostics.Category.CONTEXT_ROUND_LIMIT);
        assertThat(snapshot.repeatedRequestOccurrences()).isEqualTo(1);
        assertThat(snapshot.repeatedRequestRounds()).isEqualTo(1);
        assertThat(snapshot.details()).doesNotContain("secret.java");
    }

    private static ImplementationPlan.ToolRequest request(String id, List<String> args) {
        return ImplementationPlan.ToolRequest.builder().id(id).tool("cat").args(args).build();
    }

    @Test
    void singletonFileIdentitiesAndExecutionSuppressionCountsAreSafeAndDistinct() {
        var collector = collector();
        collector.observeLegacyRequests(List.of(), List.of("private-A", "private-B"), "sha", 1);
        collector.requestExecuted();
        collector.requestExecuted();
        collector.observeLegacyRequests(List.of(), List.of("private-A"), "sha", 2);
        collector.requestSuppressed();
        var snapshot = collector.snapshot(ReviewDiagnostics.Category.STALLED_CONTEXT);
        assertThat(snapshot.requestOccurrences()).isEqualTo(3);
        assertThat(snapshot.repeatedRequestOccurrences()).isEqualTo(1);
        assertThat(snapshot.executedRequestOccurrences()).isEqualTo(2);
        assertThat(snapshot.suppressedRequestOccurrences()).isEqualTo(1);
        assertThat(snapshot.details()).doesNotContain("private-A", "private-B", "sha");
        assertThat(snapshot.summary()).isEqualTo("stalled-context");
        assertThat(collector.snapshot(ReviewDiagnostics.Category.MANDATORY_CONTEXT_LIMIT).summary())
                .isEqualTo("mandatory-context-limit");
    }

    private static ReviewDiagnostics.Collector collector() {
        return new ReviewDiagnostics.Collector(10, 20, 4096,
                7L, "owner", "repo", 12L, "llamacpp", 3L);
    }
}
