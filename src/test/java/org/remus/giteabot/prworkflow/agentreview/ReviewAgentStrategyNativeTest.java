package org.remus.giteabot.prworkflow.agentreview;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.agent.issueimpl.AiResponseParser;
import org.remus.giteabot.agent.loop.AgentRunContext;
import org.remus.giteabot.agent.loop.StepDecision;
import org.remus.giteabot.agent.shared.AgentJackson;
import org.remus.giteabot.agent.shared.BranchSwitcher;
import org.remus.giteabot.agent.tools.AgentToolRouter;
import org.remus.giteabot.agent.tools.ToolCallContext;
import org.remus.giteabot.agent.tools.ToolCatalog;
import org.remus.giteabot.agent.validation.ToolExecutionService;
import org.remus.giteabot.agent.validation.ToolResult;
import org.remus.giteabot.ai.ChatTurn;
import org.remus.giteabot.ai.StopReason;
import org.remus.giteabot.ai.ToolCall;
import org.remus.giteabot.config.AgentConfigProperties;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewAgentStrategyNativeTest {

    private static final ToolResult READ = new ToolResult(true, 0, "file contents", "");
    private static final ToolResult SWITCH = new ToolResult(false, -1, "", "Denied in review mode");

    @Mock
    private AgentToolRouter toolRouter;

    @Test
    void deniedSwitchKeepsDiagnosticRefAcrossTurns() {
        var diagnostics = collector();
        var strategy = strategy(diagnostics);
        var context = context();
        when(toolRouter.execute(eq(AgentToolRouter.Mode.REVIEW), any()))
                .thenReturn(READ, SWITCH, READ, READ);

        strategy.step(context, turn(read("read-1")), 1);
        strategy.step(context, turn(switchBranch()), 2);
        strategy.step(context, turn(read("read-2")), 3);
        assertThat(snapshot(diagnostics).repeatedRequestOccurrences()).isEqualTo(1);
        strategy.step(context, turn(read("read-3")), 4);

        assertThat(snapshot(diagnostics).requestOccurrences()).isEqualTo(4);
        assertThat(snapshot(diagnostics).contextRounds()).isEqualTo(4);
        assertThat(snapshot(diagnostics).repeatedRequestOccurrences()).isEqualTo(2);
        assertThat(snapshot(diagnostics).repeatedRequestRounds()).isEqualTo(2);
        assertThat(context.baseBranch()).isEqualTo("refs/heads/main");
        assertThat(strategy.onBudgetExhausted(context).selectedBranch()).isEqualTo("refs/heads/main");
        assertDispatch("cat", "branch-switcher", "cat", "cat");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deniedSwitchAndReadsInSameBatchStayOnReviewedRevision(boolean readBeforeSwitch) {
        var diagnostics = collector();
        var strategy = strategy(diagnostics);
        var context = context();
        List<String> executed = new ArrayList<>();
        when(toolRouter.execute(eq(AgentToolRouter.Mode.REVIEW), any()))
                .thenAnswer(invocation -> {
                    ToolCallContext call = invocation.getArgument(1);
                    executed.add(call.tool());
                    assertThat(context.baseBranch()).isEqualTo("refs/heads/main");
                    return call.tool().equals("branch-switcher") ? SWITCH : READ;
                });
        strategy.step(context, turn(read("initial")), 1);
        List<ToolCall> batch = new ArrayList<>();
        if (readBeforeSwitch) {
            batch.add(read("before-switch"));
        }
        batch.add(switchBranch());
        batch.add(read("after-switch"));

        var decision = (StepDecision.ContinueWithToolResults) strategy.step(context,
                new ChatTurn("", batch, StopReason.TOOL_USE, 100, 10), 2);

        assertThat(snapshot(diagnostics).requestOccurrences()).isEqualTo(readBeforeSwitch ? 4 : 3);
        assertThat(snapshot(diagnostics).repeatedRequestOccurrences()).isEqualTo(readBeforeSwitch ? 2 : 1);
        assertThat(decision.results()).extracting(StepDecision.ToolCallResult::toolCallId)
                .containsExactlyElementsOf(batch.stream().map(ToolCall::id).toList());
        assertThat(decision.results()).extracting(StepDecision.ToolCallResult::resultText)
                .containsExactlyElementsOf(batch.stream()
                        .map(call -> call.name().equals("branch-switcher") ? SWITCH : READ)
                        .map(ToolResult::formatForAi).toList());
        strategy.step(context, turn(read("repeat-after-switch")), 3);
        assertThat(snapshot(diagnostics).requestOccurrences()).isEqualTo(readBeforeSwitch ? 5 : 4);
        assertThat(snapshot(diagnostics).repeatedRequestOccurrences()).isEqualTo(readBeforeSwitch ? 3 : 2);
        assertThat(snapshot(diagnostics).repeatedRequestRounds()).isEqualTo(2);
        assertThat(context.baseBranch()).isEqualTo("refs/heads/main");
        assertThat(executed).containsExactlyElementsOf(readBeforeSwitch
                ? List.of("cat", "cat", "branch-switcher", "cat", "cat")
                : List.of("cat", "branch-switcher", "cat", "cat"));
        assertDispatch(executed.toArray(String[]::new));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedOrUnannouncedSwitchDoesNotChangeDiagnosticRef(boolean successful) {
        var diagnostics = collector();
        var strategy = strategy(diagnostics);
        var context = context();
        ToolResult switchResult = successful
                ? new ToolResult(true, 0, "No branch announcement", "")
                : new ToolResult(false, 1, SWITCH.output(), "Switch failed");
        when(toolRouter.execute(eq(AgentToolRouter.Mode.REVIEW), any()))
                .thenReturn(READ, switchResult, READ);

        strategy.step(context, turn(read("initial")), 1);
        strategy.step(context, turn(switchBranch(), read("after-switch")), 2);

        assertThat(snapshot(diagnostics).requestOccurrences()).isEqualTo(3);
        assertThat(snapshot(diagnostics).repeatedRequestOccurrences()).isEqualTo(1);
        assertThat(snapshot(diagnostics).repeatedRequestRounds()).isEqualTo(1);
        assertThat(context.baseBranch()).isEqualTo("refs/heads/main");
        assertDispatch("cat", "branch-switcher", "cat");
    }

    @Test
    void disabledDiagnosticsPreserveNativeDispatchAndBranchSemantics() {
        var strategy = strategy(null);
        var context = context();
        when(toolRouter.execute(eq(AgentToolRouter.Mode.REVIEW), any())).thenReturn(SWITCH, READ);

        var decision = (StepDecision.ContinueWithToolResults) strategy.step(context,
                turn(switchBranch(), read("after-switch")), 1);

        assertThat(decision.results()).extracting(StepDecision.ToolCallResult::resultText)
                .containsExactly(SWITCH.formatForAi(), READ.formatForAi());
        assertThat(context.baseBranch()).isEqualTo("refs/heads/main");
        assertDispatch("branch-switcher", "cat");
    }

    private void assertDispatch(String... tools) {
        var calls = ArgumentCaptor.forClass(ToolCallContext.class);
        verify(toolRouter, times(tools.length)).execute(eq(AgentToolRouter.Mode.REVIEW), calls.capture());
        assertThat(calls.getAllValues()).extracting(ToolCallContext::tool).containsExactly(tools);
        assertThat(calls.getAllValues()).allSatisfy(call -> {
            assertThat(call.workspaceDir()).isEqualTo(Path.of("workspace"));
            assertThat(call.args()).containsExactly(call.tool().equals("branch-switcher")
                    ? "refs/heads/other" : "Example.java");
        });
        verifyNoMoreInteractions(toolRouter);
    }

    private ReviewAgentStrategy strategy(ReviewDiagnostics.Collector diagnostics) {
        return new ReviewAgentStrategy("system", toolRouter, new ToolCatalog(new AgentConfigProperties()),
                null, null, new AiResponseParser(), new BranchSwitcher(mock(ToolExecutionService.class)),
                null, 5, diagnostics);
    }

    private AgentRunContext context() {
        return new AgentRunContext(null, "owner", "repo", 1L, Path.of("workspace"), "refs/heads/main");
    }

    private static ReviewDiagnostics.Collector collector() {
        return new ReviewDiagnostics.Collector(5, 10, 4096,
                1L, "owner", "repo", 1L, null, null);
    }

    private static ReviewDiagnostics.Snapshot snapshot(ReviewDiagnostics.Collector diagnostics) {
        return diagnostics.snapshot(ReviewDiagnostics.Category.LOOP_ROUND_LIMIT);
    }

    private static ChatTurn turn(ToolCall... calls) {
        return new ChatTurn("", List.of(calls), StopReason.TOOL_USE, 100, 10);
    }

    private static ToolCall read(String id) {
        return new ToolCall(id, "cat", AgentJackson.mapper().createObjectNode().put("path", "Example.java"));
    }

    private static ToolCall switchBranch() {
        return new ToolCall("switch", "branch-switcher",
                AgentJackson.mapper().createObjectNode().put("branch", "refs/heads/other"));
    }
}
