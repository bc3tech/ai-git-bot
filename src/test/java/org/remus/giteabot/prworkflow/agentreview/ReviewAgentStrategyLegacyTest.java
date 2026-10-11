package org.remus.giteabot.prworkflow.agentreview;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.agent.issueimpl.AiResponseParser;
import org.remus.giteabot.agent.loop.AgentBudget;
import org.remus.giteabot.agent.loop.AgentLoop;
import org.remus.giteabot.agent.loop.AgentRunContext;
import org.remus.giteabot.agent.loop.LoopOutcome;
import org.remus.giteabot.agent.loop.StepDecision;
import org.remus.giteabot.agent.loop.ContextBudgetExceededException;
import org.remus.giteabot.agent.session.AgentSession;
import org.remus.giteabot.agent.session.AgentSessionRepository;
import org.remus.giteabot.agent.session.AgentSessionService;
import org.remus.giteabot.agent.shared.AgentJackson;
import org.remus.giteabot.agent.shared.BranchSwitcher;
import org.remus.giteabot.agent.tools.AgentToolRouter;
import org.remus.giteabot.agent.tools.ToolCatalog;
import org.remus.giteabot.agent.validation.ToolExecutionService;
import org.remus.giteabot.agent.validation.ToolResult;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.ai.AiMessage;
import org.remus.giteabot.ai.ChatTurn;
import org.remus.giteabot.ai.StopReason;
import org.remus.giteabot.ai.ToolCall;
import org.remus.giteabot.ai.openai.OpenAiClient;
import org.remus.giteabot.ai.openai.OpenAiFlavor;
import org.remus.giteabot.config.AgentConfigProperties;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpClientErrorException;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
class ReviewAgentStrategyLegacyTest {

    @Mock
    private AgentToolRouter toolRouter;

    @Mock
    private ToolExecutionService toolExecutionService;

    private ReviewAgentStrategy strategy() {
        return new ReviewAgentStrategy(
                "system",
                toolRouter,
                new ToolCatalog(new AgentConfigProperties()),
                null,                 // mcp catalog -> defaults to empty
                null,                 // no whitelist
                new AiResponseParser(),
                new BranchSwitcher(toolExecutionService),
                (owner, repo, ref, files) -> "FILE-CONTENT",
                5);
    }

    private AgentRunContext ctx() {
        return new AgentRunContext(null, "octo", "repo", 1L, Path.of("/tmp/ws"), "main");
    }

    @Test
    void legacyBranchRequestGoesThroughReviewRouterWithoutSwitchingRevision() {
        when(toolRouter.execute(eq(AgentToolRouter.Mode.REVIEW), any()))
                .thenReturn(new org.remus.giteabot.agent.validation.ToolResult(false, -1, "", "Denied"));
        var context = ctx();
        var decision = strategy().step(context,
                "{\"runTools\":[{\"tool\":\"branch-switcher\",\"args\":[\"other\"]}]}", 1);
        assertInstanceOf(StepDecision.Continue.class, decision);
        assertEquals("main", context.baseBranch());
        verifyNoInteractions(toolExecutionService);
        verify(toolRouter).execute(eq(AgentToolRouter.Mode.REVIEW), any());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"length", "unknown", "tool_calls"})
    void legacyLoopRejectsIncompleteProviderResponseBeforeParsing(String finishReason) {
        RestClient.Builder http = RestClient.builder().baseUrl("https://provider.example");
        MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
        var response = AgentJackson.mapper().createObjectNode();
        var choice = response.putArray("choices").addObject();
        choice.put("finish_reason", finishReason);
        choice.putObject("message").put("content", "A partial review");
        response.putObject("usage").put("prompt_tokens", 100).put("completion_tokens", 8);
        server.expect(requestTo("https://provider.example/v1/chat/completions"))
                .andExpect(jsonPath("$.tools").doesNotExist())
                .andRespond(withSuccess(response.toString(), MediaType.APPLICATION_JSON));
        AiClient client = new OpenAiClient(http.build(), "test-model", 32, false, OpenAiFlavor.STANDARD);
        AiResponseParser parser = mock(AiResponseParser.class);
        ReviewAgentStrategy.FileFetcher fetcher = mock(ReviewAgentStrategy.FileFetcher.class);
        ReviewAgentStrategy strategy = new ReviewAgentStrategy("system", toolRouter,
                new ToolCatalog(new AgentConfigProperties()), null, null, parser,
                new BranchSwitcher(toolExecutionService), fetcher, 5);
        AgentSession session = new AgentSession("octo", "repo", 1L, "Review example");
        AgentRunContext context = new AgentRunContext(session, "octo", "repo", 1L, Path.of("/tmp/ws"), "main");
        AgentLoop loop = new AgentLoop(client, new AgentSessionService(mock(AgentSessionRepository.class)),
                new AgentBudget(3, 2, 2, 32, 8_000, 120_000, 200_000, 0.7));

        LoopOutcome outcome = loop.run(context, "Review the change", strategy);

        assertFalse(outcome.success());
        verifyNoInteractions(parser, toolRouter, toolExecutionService, fetcher);
        assertEquals(100L, session.getTotalInputTokens());
        assertEquals(8L, session.getTotalOutputTokens());
        server.verify();
    }

    @Test
    void legacyLoopFetchesRequestedFilesBeforeAcceptingTheFinalReview() {
        AiClient client = mock(AiClient.class);
        when(client.chatWithTools(anyList(), anyString(), eq(List.of()), anyString(), isNull(), anyInt()))
                .thenReturn(new ChatTurn("{\"summary\":\"Let me inspect the file\",\"requestFiles\":[\"Example.java\"]}",
                                List.of(), StopReason.END_TURN, 100, 12),
                        new ChatTurn("The change correctly handles missing values.", List.of(), StopReason.END_TURN, 120, 20));
        AgentSession session = new AgentSession("octo", "repo", 1L, "Review example");
        AgentRunContext context = new AgentRunContext(session, "octo", "repo", 1L,
                Path.of("/tmp/ws"), "main");
        AgentLoop loop = new AgentLoop(client, new AgentSessionService(mock(AgentSessionRepository.class)),
                new AgentBudget(3, 2, 2, 4000, 8_000, 120_000, 200_000, 0.7));

        LoopOutcome outcome = loop.run(context, "Review the change", strategy());

        assertTrue(outcome.success());
        assertEquals("The change correctly handles missing values.", outcome.payload());
        assertEquals(220L, session.getTotalInputTokens());
        assertEquals(32L, session.getTotalOutputTokens());
        verify(client).chatWithTools(anyList(), contains("FILE-CONTENT"), eq(List.of()), anyString(), isNull(), anyInt());
        verify(client, times(2)).chatWithTools(anyList(), anyString(), eq(List.of()), anyString(), isNull(), anyInt());
    }

    @Test
    void legacy_contextRequest_executesToolsAndContinues() {
        when(toolRouter.execute(any(), any()))
                .thenReturn(new ToolResult(true, 0, "tool output", ""));

        String json = "{\"summary\":\"exploring\",\"requestTools\":[{\"tool\":\"cat\",\"args\":[\"README.md\"]}]}";
        StepDecision decision = strategy().stepLegacy(ctx(), ChatTurn.text(json), 1);

        // Read-only tool was executed and the loop continues to gather more context.
        verify(toolRouter, times(1)).execute(any(), any());
        StepDecision.Continue cont = assertInstanceOf(StepDecision.Continue.class, decision);
        assertTrue(cont.nextUserMessage().contains("requested repository context"));
        assertTrue(cont.nextUserMessage().contains("tool output"));
    }

    @Test
    void legacy_plainText_isTreatedAsFinalReview() {
        StepDecision decision = strategy().stepLegacy(ctx(), ChatTurn.text("LGTM — the change looks correct."), 1);

        verify(toolRouter, never()).execute(any(), any());
        StepDecision.Finish finish = assertInstanceOf(StepDecision.Finish.class, decision);
        assertTrue(finish.outcome().success());
        assertEquals("LGTM — the change looks correct.", finish.outcome().payload());
    }

    @Test
    void legacyCannotExecuteUnexpectedNativeCallsEvenWithEndTurn() {
        ChatTurn turn = new ChatTurn("Partial review",
                List.of(new ToolCall("call-1", "cat", AgentJackson.mapper().createObjectNode())),
                StopReason.END_TURN, 100, 8);

        StepDecision.Finish finish = assertInstanceOf(StepDecision.Finish.class,
                strategy().stepLegacy(ctx(), turn, 1));

        assertFalse(finish.outcome().success());
        verifyNoInteractions(toolRouter, toolExecutionService);
    }

    @Test
    void legacyFailuresRetainDistinctCompletionCategories() {
        var truncated = (StepDecision.Finish) strategy().stepLegacy(ctx(),
                new ChatTurn("partial", List.of(), StopReason.MAX_TOKENS, 100, 32), 1);
        var unknown = (StepDecision.Finish) strategy().stepLegacy(ctx(),
                new ChatTurn("partial", List.of(), StopReason.OTHER, 100, 32), 1);

        assertEquals(ReviewDiagnostics.Category.OUTPUT_TOKEN_LIMIT,
                ((ReviewDiagnostics.Snapshot) truncated.outcome().payload()).category());
        assertEquals(ReviewDiagnostics.Category.UNKNOWN_COMPLETION,
                ((ReviewDiagnostics.Snapshot) unknown.outcome().payload()).category());
    }

    @Test
    void legacy_contextLimitDoesNotTurnAnotherRequestIntoAReview() {
        when(toolRouter.execute(any(), any()))
                .thenReturn(new ToolResult(true, 0, "tool output", ""));
        ReviewAgentStrategy strategy = strategy();
        String request = "{\"summary\":\"Still exploring\",\"requestTools\":[{\"tool\":\"cat\",\"args\":[\"README.md\"]}]}";
        for (int round = 1; round <= 5; round++) {
            assertInstanceOf(StepDecision.Continue.class, strategy.stepLegacy(ctx(), ChatTurn.text(request), round));
        }

        StepDecision.Finish finish = assertInstanceOf(StepDecision.Finish.class,
                strategy.stepLegacy(ctx(), ChatTurn.text(request), 6));

        assertFalse(finish.outcome().success());
        verify(toolRouter, times(5)).execute(any(), any());
    }

    @Test
    void legacySwitchIsDeniedWithoutChangingDiagnosticRefOrDoubleCountingTools() {
        when(toolRouter.execute(any(), any())).thenReturn(new ToolResult(true, 0, "tool output", ""));
        var diagnostics = new ReviewDiagnostics.Collector(5, 10, 4096,
                1L, "octo", "repo", 1L, null, null);
        var strategy = new ReviewAgentStrategy("system", toolRouter,
                new ToolCatalog(new AgentConfigProperties()), null, null, new AiResponseParser(),
                new BranchSwitcher(toolExecutionService), (owner, repo, ref, files) -> "FILE-CONTENT",
                5, diagnostics);
        var context = ctx();
        String read = """
                {"requestTools":[{"tool":"cat","args":["README.md"]}]}
                """;
        String switchAndRead = """
                {"requestTools":[{"tool":"cat","args":["README.md"]},
                  {"tool":"branch-switcher","args":["other"]}]}
                """;

        strategy.stepLegacy(context, ChatTurn.text(read), 1);
        strategy.stepLegacy(context, ChatTurn.text(switchAndRead), 2);
        assertEquals("main", context.baseBranch());
        assertEquals(1, diagnostics.snapshot(ReviewDiagnostics.Category.LOOP_ROUND_LIMIT)
                .repeatedRequestOccurrences());
        strategy.stepLegacy(context, ChatTurn.text(read), 3);

        var snapshot = diagnostics.snapshot(ReviewDiagnostics.Category.LOOP_ROUND_LIMIT);
        assertEquals(4, snapshot.requestOccurrences());
        assertEquals(3, snapshot.contextRounds());
        assertEquals(2, snapshot.repeatedRequestOccurrences());
        assertEquals(2, snapshot.repeatedRequestRounds());
        verify(toolRouter, times(4)).execute(eq(AgentToolRouter.Mode.REVIEW), any());
        verifyNoInteractions(toolExecutionService);
    }

    @Test
    void onBudgetExhausted_returnsWarning() {
        ReviewAgentStrategy strategy = strategy();
        LoopOutcome outcome = strategy.onBudgetExhausted(ctx());
        assertFalse(outcome.success());
        assertEquals(ReviewDiagnostics.Category.LOOP_ROUND_LIMIT,
                ((ReviewDiagnostics.Snapshot) outcome.payload()).category());
    }

    @Test
    void finishWithBlank_returnsWarning() {
        ReviewAgentStrategy strategy = strategy();
        // Passing null/blank as the AI response which will end up calling finish() with blank
        StepDecision decision = strategy.stepLegacy(ctx(), ChatTurn.text(""), 1);
        StepDecision.Finish finish = assertInstanceOf(StepDecision.Finish.class, decision);
        assertFalse(finish.outcome().success());
        assertEquals(ReviewDiagnostics.Category.EMPTY_FEEDBACK,
                ((ReviewDiagnostics.Snapshot) finish.outcome().payload()).category());
    }

    private ReviewAgentStrategy recovering(int cap, ReviewAgentStrategy.FileEvidenceFetcher fetcher,
                                           ReviewDiagnostics.Collector diagnostics) {
        return new ReviewAgentStrategy("system", toolRouter,
                new ToolCatalog(new AgentConfigProperties()), null, null, new AiResponseParser(),
                new BranchSwitcher(toolExecutionService), (owner, repo, ref, files) -> "untyped",
                5, diagnostics).enableLegacyRecovery(cap).withFileEvidenceFetcher(fetcher);
    }

    private String prepare(ReviewAgentStrategy strategy, List<AiMessage> history, String current,
                           int maxChars, boolean aggressive) {
        AiClient client = mock(AiClient.class);
        when(client.estimateRequestTokens(anyList(), anyString(), anyList(), anyString()))
                .thenAnswer(invocation -> (long) invocation.<String>getArgument(1).length());
        return strategy.prepareRequest(ctx(), history, current, "system", List.of(),
                new AgentBudget(10, 5, 2, 100, 8_000, maxChars, 100_000, 0.7),
                client, aggressive);
    }

    private StepDecision request(ReviewAgentStrategy strategy, int round, String... files) {
        String json = AgentJackson.mapper().createObjectNode().putPOJO("requestFiles", List.of(files)).toString();
        return strategy.stepLegacy(ctx(), ChatTurn.text(json), round);
    }

    @Test
    void recoverySplitsFilesSuppressesOverlapAndStopsAfterOneNonProgressOpportunity() {
        var fetcher = mock(ReviewAgentStrategy.FileEvidenceFetcher.class);
        when(fetcher.fetch(anyString(), anyString(), eq("main"), anyString()))
                .thenAnswer(invocation -> ReviewAgentStrategy.FileEvidence.complete(
                        "source-" + invocation.<String>getArgument(3)));
        var diagnostics = new ReviewDiagnostics.Collector(5, 10, 100,
                1L, "octo", "repo", 1L, null, null);
        var strategy = recovering(10_000, fetcher, diagnostics);
        List<AiMessage> history = new ArrayList<>();
        prepare(strategy, history, "Review immutable change", 20_000, false);
        var first = assertInstanceOf(StepDecision.Continue.class, request(strategy, 1, "A"));
        history.add(AiMessage.builder().role("user").content(first.nextUserMessage()).build());
        history.add(AiMessage.builder().role("assistant").content("old evidence acknowledgement").build());
        var second = assertInstanceOf(StepDecision.Continue.class, request(strategy, 2, "A", "B"));
        String wire = prepare(strategy, history, second.nextUserMessage(), 20_000, false);

        assertTrue(history.isEmpty());
        assertTrue(wire.contains("Review immutable change"));
        assertTrue(wire.contains("Immutable source ref: main"));
        assertEquals(1, wire.split("source-A", -1).length - 1);
        assertEquals(1, wire.split("source-B", -1).length - 1);
        assertFalse(wire.contains("old evidence acknowledgement"));
        assertInstanceOf(StepDecision.Continue.class, request(strategy, 3, "A", "B"));
        var stalled = assertInstanceOf(StepDecision.Finish.class, request(strategy, 4, "A"));
        assertEquals(ReviewDiagnostics.Category.STALLED_CONTEXT,
                ((ReviewDiagnostics.Snapshot) stalled.outcome().payload()).category());
        verify(fetcher).fetch("octo", "repo", "main", "A");
        verify(fetcher).fetch("octo", "repo", "main", "B");
        var snapshot = diagnostics.snapshot(ReviewDiagnostics.Category.STALLED_CONTEXT);
        assertEquals(6, snapshot.requestOccurrences());
        assertEquals(2, snapshot.executedRequestOccurrences());
        assertEquals(4, snapshot.suppressedRequestOccurrences());
        assertTrue(strategy.coverageNotes().isEmpty());
    }

    @Test
    void usableRestorationResetsRecoveryAndEvictionInvalidatesAcknowledgementsWithoutLosingCoverage() {
        var fetcher = mock(ReviewAgentStrategy.FileEvidenceFetcher.class);
        when(fetcher.fetch(anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> ReviewAgentStrategy.FileEvidence.complete(
                        invocation.<String>getArgument(3).repeat(600)));
        var strategy = recovering(1_000, fetcher, null);
        prepare(strategy, new ArrayList<>(), "Review pinned task", 20_000, false);
        request(strategy, 1, "A");
        request(strategy, 2, "A");
        var replacing = assertInstanceOf(StepDecision.Continue.class, request(strategy, 3, "A", "B"));
        String wire = prepare(strategy, new ArrayList<>(), replacing.nextUserMessage(), 20_000, false);
        assertFalse(wire.contains("source=requestFiles A"));
        assertFalse(wire.contains("### E1"));
        assertTrue(wire.contains("source=requestFiles B"));
        assertTrue(wire.contains("Consecutive non-progress context turns:0"));
        assertTrue(strategy.coverageNotes().isEmpty());
        var restored = assertInstanceOf(StepDecision.Continue.class, request(strategy, 4, "A"));
        assertTrue(restored.nextUserMessage().contains("source=requestFiles A"));
        verify(fetcher, times(2)).fetch("octo", "repo", "main", "A");
    }

    @Test
    void identicalRetainedExcerptIsSuppressedButOmittedLineReadMakesProgress() {
        var fetcher = mock(ReviewAgentStrategy.FileEvidenceFetcher.class);
        when(fetcher.fetch(anyString(), anyString(), anyString(), eq("A")))
                .thenReturn(new ReviewAgentStrategy.FileEvidence(true, false,
                        "first line\n".repeat(100), 1, 200));
        when(toolRouter.execute(eq(AgentToolRouter.Mode.REVIEW), any()))
                .thenReturn(new ToolResult(true, 0, "150 | restored omitted line\n", ""));
        var strategy = recovering(900, fetcher, null);
        prepare(strategy, new ArrayList<>(), "Review task", 20_000, false);
        var first = assertInstanceOf(StepDecision.Continue.class, request(strategy, 1, "A"));
        assertTrue(first.nextUserMessage().contains("Omitted:"));
        var duplicate = assertInstanceOf(StepDecision.Continue.class, request(strategy, 2, "A"));
        assertTrue(duplicate.nextUserMessage().contains("repeating this request cannot restore omitted lines"));
        var narrowed = assertInstanceOf(StepDecision.Continue.class,
                strategy.stepLegacy(ctx(), ChatTurn.text("""
                        {"runTools":[{"id":"new-id","tool":"cat","args":["A","150","150"]}]}
                        """), 3));
        assertTrue(narrowed.nextUserMessage().contains("150 | restored omitted line"));
        assertTrue(narrowed.nextUserMessage().contains("Consecutive non-progress context turns:0"));
        assertFalse(strategy.coverageNotes().isEmpty());
        verify(fetcher).fetch("octo", "repo", "main", "A");
        verify(toolRouter).execute(eq(AgentToolRouter.Mode.REVIEW), any());
    }

    @Test
    void duplicatePlusFailedReadDoesNotResetRecoveryAndKeepsSafeCoverageNotes() {
        var fetcher = mock(ReviewAgentStrategy.FileEvidenceFetcher.class);
        when(fetcher.fetch(anyString(), anyString(), anyString(), eq("A")))
                .thenReturn(ReviewAgentStrategy.FileEvidence.complete("usable evidence"));
        when(fetcher.fetch(anyString(), anyString(), anyString(), eq("missing-secret-path")))
                .thenReturn(ReviewAgentStrategy.FileEvidence.failed());
        var strategy = recovering(3_000, fetcher, null);
        prepare(strategy, new ArrayList<>(), "Review task", 20_000, false);
        request(strategy, 1, "A");
        request(strategy, 2, "A", "missing-secret-path");
        var stalled = assertInstanceOf(StepDecision.Finish.class, request(strategy, 3, "A", "missing-secret-path"));
        assertEquals(ReviewDiagnostics.Category.STALLED_CONTEXT,
                ((ReviewDiagnostics.Snapshot) stalled.outcome().payload()).category());
        assertFalse(strategy.coverageNotes().isEmpty());
        assertFalse(String.join("", strategy.coverageNotes()).contains("missing-secret-path"));
        verify(fetcher, times(2)).fetch("octo", "repo", "main", "missing-secret-path");
    }

    @Test
    void failedOnlyReadsAreBoundedAndEmptyVerifiedSourceIsNotAMissingFile() {
        var failed = recovering(3_000, (owner, repo, ref, path) -> ReviewAgentStrategy.FileEvidence.failed(), null);
        assertInstanceOf(StepDecision.Continue.class, request(failed, 1, "missing"));
        assertInstanceOf(StepDecision.Finish.class, request(failed, 2, "missing"));
        var empty = recovering(3_000, (owner, repo, ref, path) -> ReviewAgentStrategy.FileEvidence.complete(""), null);
        var delivered = assertInstanceOf(StepDecision.Continue.class, request(empty, 1, "empty"));
        assertTrue(delivered.nextUserMessage().contains("verified empty"));
        assertTrue(empty.coverageNotes().isEmpty());
        assertFalse(ReviewAgentStrategy.FileEvidence.complete(null).success());
    }

    @Test
    void preparationChecksMandatoryContextAndSerializedEstimateWithOutputHeadroom() {
        var strategy = recovering(10_000,
                (owner, repo, ref, path) -> ReviewAgentStrategy.FileEvidence.complete("line\n".repeat(2_000)), null);
        AiClient unusedEstimator = mock(AiClient.class);
        assertThrows(ContextBudgetExceededException.class,
                () -> strategy.prepareRequest(ctx(), new ArrayList<>(), "mandatory task".repeat(100),
                        "system", List.of(), new AgentBudget(10, 5, 2, 100, 8_000, 200, 100_000, 0.7),
                        unusedEstimator, false));
        verifyNoInteractions(unusedEstimator);
        var fit = recovering(10_000,
                (owner, repo, ref, path) -> ReviewAgentStrategy.FileEvidence.complete("line\n".repeat(2_000)), null);
        prepare(fit, new ArrayList<>(), "task", 3_000, false);
        var context = assertInstanceOf(StepDecision.Continue.class, request(fit, 1, "A"));
        String wire = prepare(fit, new ArrayList<>(), context.nextUserMessage(), 3_000, false);
        assertTrue(wire.length() <= 3_000);
        assertTrue(wire.contains("Omitted:"));
        AiClient hugeWrapper = mock(AiClient.class);
        when(hugeWrapper.estimateRequestTokens(anyList(), anyString(), anyList(), anyString())).thenReturn(1_000L);
        assertThrows(ContextBudgetExceededException.class, () -> fit.prepareRequest(ctx(), new ArrayList<>(),
                wire, "system", List.of(), new AgentBudget(10, 5, 2, 300, 8_000, 3_000, 1_000, 0.7),
                hugeWrapper, false));
    }

    @Test
    void generatedIdsDoNotReplayToolsButChangedArgumentsRemainEligible() {
        when(toolRouter.execute(eq(AgentToolRouter.Mode.REVIEW), any()))
                .thenReturn(new ToolResult(true, 0, "1 | complete\n", ""),
                        new ToolResult(true, 0, "2 | narrow\n", ""));
        var strategy = recovering(3_000, (owner, repo, ref, path) -> ReviewAgentStrategy.FileEvidence.failed(), null);
        String first = """
                {"runTools":[{"id":"one","tool":"cat","args":["A"]}]}
                """;
        strategy.stepLegacy(ctx(), ChatTurn.text(first), 1);
        strategy.stepLegacy(ctx(), ChatTurn.text(first.replace("one", "two")), 2);
        var narrower = assertInstanceOf(StepDecision.Continue.class, strategy.stepLegacy(ctx(), ChatTurn.text("""
                {"runTools":[{"id":"three","tool":"cat","args":["A","2","2"]}]}
                """), 3));
        assertTrue(narrower.nextUserMessage().contains("2 | narrow"));
        verify(toolRouter, times(2)).execute(eq(AgentToolRouter.Mode.REVIEW), any());
    }

    @Test
    void recoveryDoesNotRaiseExistingContextCapAndRemainsDefaultOff() {
        assertFalse(strategy().usesRequestPreparation());
        var recovering = recovering(3_000,
                (owner, repo, ref, path) -> ReviewAgentStrategy.FileEvidence.complete(path), null);
        for (int round = 1; round <= 5; round++) {
            assertInstanceOf(StepDecision.Continue.class, request(recovering, round, "file-" + round));
        }
        var capped = assertInstanceOf(StepDecision.Finish.class, request(recovering, 6, "file-6"));
        assertEquals(ReviewDiagnostics.Category.CONTEXT_ROUND_LIMIT,
                ((ReviewDiagnostics.Snapshot) capped.outcome().payload()).category());
    }

    @Test
    void fullyFetchedOversizedFileStillDisclosesUndeliveredRegionsAfterCompletedReview() {
        var strategy = recovering(900,
                (owner, repo, ref, path) -> ReviewAgentStrategy.FileEvidence.complete(
                        "full source in fetch cache\n".repeat(10_000)), null);
        prepare(strategy, new ArrayList<>(), "Review pinned task", 3_000, false);
        var context = assertInstanceOf(StepDecision.Continue.class, request(strategy, 1, "large"));
        String wire = prepare(strategy, new ArrayList<>(), context.nextUserMessage(), 3_000, false);
        assertTrue(wire.contains("Omitted:"));
        assertFalse(strategy.coverageNotes().isEmpty());
        var completed = assertInstanceOf(StepDecision.Finish.class,
                strategy.stepLegacy(ctx(), ChatTurn.text("Review completed with available evidence."), 2));
        assertTrue(completed.outcome().success());
        assertFalse(strategy.coverageNotes().isEmpty());
    }

    @Test
    void providerRetryShrinkingCompleteCandidateDoesNotKeepItsProvisionalCleanCoverage() {
        var strategy = recovering(2_000,
                (owner, repo, ref, path) -> ReviewAgentStrategy.FileEvidence.complete("line\n".repeat(100)), null);
        prepare(strategy, new ArrayList<>(), "Review pinned task", 4_000, false);
        var context = assertInstanceOf(StepDecision.Continue.class, request(strategy, 1, "A"));
        String firstAttempt = prepare(strategy, new ArrayList<>(), context.nextUserMessage(), 4_000, false);
        assertTrue(strategy.coverageNotes().isEmpty());
        String retried = prepare(strategy, new ArrayList<>(), firstAttempt, 1_350, true);
        assertTrue(retried.length() <= 1_350);
        assertFalse(strategy.coverageNotes().isEmpty());
        strategy.stepLegacy(ctx(), ChatTurn.text("Completed review."), 2);
        assertFalse(strategy.coverageNotes().isEmpty());
    }

    @Test
    void realLoopStopsBeforeRetryWhenFinalRetentionCreatesSecondConsecutiveNonProgress() {
        AiClient client = mock(AiClient.class);
        AtomicBoolean rejected = new AtomicBoolean();
        AtomicInteger calls = new AtomicInteger();
        HttpClientErrorException rejection = HttpClientErrorException.create(HttpStatus.BAD_REQUEST,
                "Bad Request", HttpHeaders.EMPTY, "prompt too long".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
        when(client.isPromptTooLongError(rejection)).thenReturn(true);
        when(client.estimateRequestTokens(anyList(), anyString(), anyList(), anyString()))
                .thenAnswer(invocation -> {
                    String current = invocation.getArgument(1);
                    return rejected.get() && current.contains("NEW-B") ? 100_000L : (long) current.length();
                });
        when(client.chatWithTools(anyList(), anyString(), anyList(), anyString(), isNull(), anyInt()))
                .thenAnswer(invocation -> switch (calls.incrementAndGet()) {
                    case 1, 2 -> ChatTurn.text("{\"requestFiles\":[\"A\"]}");
                    case 3 -> ChatTurn.text("{\"requestFiles\":[\"B\"]}");
                    default -> {
                        rejected.set(true);
                        throw rejection;
                    }
                });
        var strategy = recovering(3_000, (owner, repo, ref, path) ->
                ReviewAgentStrategy.FileEvidence.complete(path.equals("B") ? "NEW-B\n" : "old-A\n"), null);
        AgentSession session = new AgentSession("octo", "repo", 1L, "Review");
        AgentRunContext context = new AgentRunContext(session, "octo", "repo", 1L,
                Path.of("review-workspace"), "main");
        AgentLoop loop = new AgentLoop(client, new AgentSessionService(mock(AgentSessionRepository.class)),
                new AgentBudget(10, 5, 2, 100, 8_000, 20_000, 100_000, 0.7));
        assertThrows(StalledReviewContextException.class, () -> loop.run(context, "Review task", strategy));
        assertEquals(4, calls.get());
    }
}
