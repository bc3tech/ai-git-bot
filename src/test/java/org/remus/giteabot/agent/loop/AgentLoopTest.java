package org.remus.giteabot.agent.loop;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.agent.session.AgentSession;
import org.remus.giteabot.agent.session.AgentSessionService;
import org.remus.giteabot.agent.session.PendingMessage;
import org.remus.giteabot.ai.AiAuditContext;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.ai.AiMessage;
import org.remus.giteabot.ai.ChatTurn;
import org.remus.giteabot.ai.StopReason;
import org.remus.giteabot.ai.ToolDescriptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.HttpClientErrorException;

import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Characterization tests for {@link AgentLoop}: round budget, history/session
 * synchronisation, Continue vs Finish dispatch.
 */
@ExtendWith(MockitoExtension.class)
class AgentLoopTest {

    @Mock private AiClient aiClient;
    @Mock private AgentSessionService sessionService;

    private AgentRunContext ctx;

    @BeforeEach
    void setUp() {
        AgentSession session = new AgentSession("owner", "repo", 42L, "title");
        session.setId(1L); // persisted session — the loop flushes id-bearing sessions
        ctx = new AgentRunContext(session, "owner", "repo", 42L, Path.of("/tmp/ws"), "main");
        // lenient: the transient-session test below uses its own id-less session.
        lenient().when(sessionService.toAiMessages(session)).thenReturn(List.of());
    }

    @Test
    void run_strategyFinishesImmediately_singleAiCall() {
        AgentLoop loop = new AgentLoop(aiClient, sessionService,
                new AgentBudget(5, 3, 3, 8000,
                        8_000, 120_000,
                        200_000, 0.7));
        when(aiClient.chatWithTools(anyList(), anyString(), eq(List.of()), anyString(), isNull(), eq(8000)))
                .thenReturn(new ChatTurn("ai-final", List.of(), StopReason.END_TURN, 100, 7));

        AgentStrategy strategy = new AgentStrategy() {
            @Override public String systemPrompt() { return "sys"; }
            @Override public StepDecision step(AgentRunContext c, String r, int round) {
                assertThat(round).isEqualTo(1);
                return new StepDecision.Finish(LoopOutcome.success(c.baseBranch(), "payload"));
            }
            @Override public LoopOutcome onBudgetExhausted(AgentRunContext c) {
                throw new AssertionError("budget should not be exhausted");
            }
        };

        LoopOutcome outcome = loop.run(ctx, "go", strategy);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.payload()).isEqualTo("payload");
        verify(aiClient).chatWithTools(anyList(), anyString(), eq(List.of()), anyString(), isNull(), anyInt());
        verify(aiClient, never()).chat(anyList(), anyString(), anyString(), isNull(), anyInt());
        // The round is flushed once with both the initial user message and the
        // assistant response, instead of one transaction per message.
        verify(sessionService).flushMessages(any(), eq(List.of(
                new PendingMessage("user", "go"),
                new PendingMessage("assistant", "ai-final"))), eq(100L), eq(7L));
    }

    @Test
    void run_transientSession_neverPersists() {
        // A session with no id (e.g. the read-only review agent) must never be
        // written to the database; the loop is purely an in-memory conversation.
        AgentSession transientSession = new AgentSession("owner", "repo", 42L, "title");
        AgentRunContext transientCtx = new AgentRunContext(
                transientSession, "owner", "repo", 42L, Path.of("/tmp/ws"), "main");
        when(sessionService.toAiMessages(transientSession)).thenReturn(List.of());

        AgentLoop loop = new AgentLoop(aiClient, sessionService,
                new AgentBudget(5, 3, 3, 8000, 8_000, 120_000, 200_000, 0.7));
        when(aiClient.chatWithTools(anyList(), anyString(), eq(List.of()), anyString(), isNull(), anyInt()))
                .thenReturn(ChatTurn.text("review-text"));

        AgentStrategy strategy = new AgentStrategy() {
            @Override public String systemPrompt() { return "sys"; }
            @Override public StepDecision step(AgentRunContext c, String r, int round) {
                return new StepDecision.Finish(LoopOutcome.success(c.baseBranch(), r));
            }
            @Override public LoopOutcome onBudgetExhausted(AgentRunContext c) {
                throw new AssertionError("budget should not be exhausted");
            }
        };

        LoopOutcome outcome = loop.run(transientCtx, "go", strategy);

        assertThat(outcome.success()).isTrue();
        verify(sessionService, never()).flushMessages(any(), anyList(), anyLong(), anyLong());
    }

    @Test
    void run_strategyContinuesOnce_thenFinishes_synchronizesHistoryBetweenCalls() {
        AgentLoop loop = new AgentLoop(aiClient, sessionService,
                new AgentBudget(5, 3, 3, 8000,
                        8_000, 120_000,
                        200_000, 0.7));
        // Snapshot the history list passed to each call (Mockito captures a live reference).
        java.util.List<java.util.List<AiMessage>> historySnapshots = new java.util.ArrayList<>();
        java.util.List<String> userMessages = new java.util.ArrayList<>();
        when(aiClient.chatWithTools(anyList(), anyString(), eq(List.of()), anyString(), isNull(), anyInt()))
                .thenAnswer(inv -> {
                    historySnapshots.add(java.util.List.copyOf(inv.getArgument(0)));
                    userMessages.add(inv.getArgument(1));
                    return ChatTurn.text(historySnapshots.size() == 1 ? "first-ai" : "second-ai");
                });

        AtomicInteger calls = new AtomicInteger();
        AgentStrategy strategy = new AgentStrategy() {
            @Override public String systemPrompt() { return "sys"; }
            @Override public StepDecision step(AgentRunContext c, String r, int round) {
                int n = calls.incrementAndGet();
                if (n == 1) return new StepDecision.Continue("follow-up-prompt");
                return new StepDecision.Finish(LoopOutcome.success(c.baseBranch(), null));
            }
            @Override public LoopOutcome onBudgetExhausted(AgentRunContext c) { return LoopOutcome.fail(c.baseBranch()); }
        };

        loop.run(ctx, "kickoff", strategy);

        // First call: empty history, user="kickoff"
        assertThat(historySnapshots.get(0)).isEmpty();
        assertThat(userMessages.getFirst()).isEqualTo("kickoff");
        // Second call: history contains the kickoff/first-ai pair, user="follow-up-prompt"
        assertThat(historySnapshots.get(1)).hasSize(2);
        assertThat(historySnapshots.get(1).get(0).getRole()).isEqualTo("user");
        assertThat(historySnapshots.get(1).get(0).getContent()).isEqualTo("kickoff");
        assertThat(historySnapshots.get(1).get(1).getRole()).isEqualTo("assistant");
        assertThat(historySnapshots.get(1).get(1).getContent()).isEqualTo("first-ai");
        assertThat(userMessages.get(1)).isEqualTo("follow-up-prompt");

        // The first round is flushed as a single batch containing the kickoff
        // user message, the assistant turn, and the follow-up user prompt.
        verify(sessionService).flushMessages(any(), eq(List.of(
                new PendingMessage("user", "kickoff"),
                new PendingMessage("assistant", "first-ai"),
                new PendingMessage("user", "follow-up-prompt"))), anyLong(), anyLong());
    }

    @Test
    void run_publishesAgentLoopRoundDuringEachCall_andClearsItAfter() {
        AgentLoop loop = new AgentLoop(aiClient, sessionService,
                new AgentBudget(5, 3, 3, 8000,
                        8_000, 120_000,
                        200_000, 0.7));
        java.util.List<Integer> roundsDuringCalls = new java.util.ArrayList<>();
        when(aiClient.chatWithTools(anyList(), anyString(), eq(List.of()), anyString(), isNull(), anyInt()))
                .thenAnswer(inv -> {
                    roundsDuringCalls.add(AiAuditContext.getRound());
                    return ChatTurn.text("ai-" + roundsDuringCalls.size());
                });

        AtomicInteger calls = new AtomicInteger();
        AgentStrategy strategy = new AgentStrategy() {
            @Override public String systemPrompt() { return "sys"; }
            @Override public StepDecision step(AgentRunContext c, String r, int round) {
                if (calls.incrementAndGet() == 1) return new StepDecision.Continue("follow-up");
                return new StepDecision.Finish(LoopOutcome.success(c.baseBranch(), null));
            }
            @Override public LoopOutcome onBudgetExhausted(AgentRunContext c) { return LoopOutcome.fail(c.baseBranch()); }
        };

        loop.run(ctx, "kickoff", strategy);

        // The audit recorder reads the round from the thread-local while the call is
        // in flight; it must be gone once the loop stops calling the provider.
        assertThat(roundsDuringCalls).containsExactly(1, 2);
        assertThat(AiAuditContext.getRound()).isNull();
    }

    @Test
    void run_budgetExhausted_invokesStrategyHook_returnsItsOutcome() {
        AgentLoop loop = new AgentLoop(aiClient, sessionService,
                new AgentBudget(2, 3, 3, 8000,
                        8_000, 120_000,
                        200_000, 0.7));
        when(aiClient.chatWithTools(anyList(), anyString(), eq(List.of()), anyString(), isNull(), anyInt()))
                .thenReturn(ChatTurn.text("a"), ChatTurn.text("b"));

        AgentStrategy strategy = new AgentStrategy() {
            @Override public String systemPrompt() { return "sys"; }
            @Override public StepDecision step(AgentRunContext c, String r, int round) {
                return new StepDecision.Continue("again-" + round);
            }
            @Override public LoopOutcome onBudgetExhausted(AgentRunContext c) {
                return LoopOutcome.fail("dead-branch");
            }
        };

        LoopOutcome outcome = loop.run(ctx, "go", strategy);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.selectedBranch()).isEqualTo("dead-branch");
        verify(aiClient, times(2)).chatWithTools(anyList(), anyString(), eq(List.of()), anyString(), isNull(), anyInt());
    }

    @Test
    void run_brokenPipeDuringAiRequest_retriesOnceAndSucceeds() {
        AgentLoop loop = new AgentLoop(aiClient, sessionService,
                new AgentBudget(5, 3, 3, 8000,
                        8_000, 120_000,
                        200_000, 0.7));
        HttpMessageNotWritableException brokenPipe = new HttpMessageNotWritableException(
                "Could not write JSON", new SocketException("Broken pipe"));
        when(aiClient.chatWithTools(anyList(), anyString(), eq(List.of()), anyString(), isNull(), anyInt()))
                .thenThrow(brokenPipe)
                .thenReturn(ChatTurn.text("ai-final"));

        LoopOutcome outcome = loop.run(ctx, "go", finishingStrategy());

        assertThat(outcome.success()).isTrue();
        verify(aiClient, times(2)).chatWithTools(anyList(), eq("go"), eq(List.of()), eq("sys"), isNull(), eq(8000));
    }

    @Test
    void run_nonNetworkSerializationFailure_doesNotRetry() {
        AgentLoop loop = new AgentLoop(aiClient, sessionService,
                new AgentBudget(5, 3, 3, 8000,
                        8_000, 120_000,
                        200_000, 0.7));
        HttpMessageNotWritableException serializationFailure =
                new HttpMessageNotWritableException("Could not write JSON");
        when(aiClient.chatWithTools(anyList(), anyString(), eq(List.of()), anyString(), isNull(), anyInt()))
                .thenThrow(serializationFailure);

        assertThatThrownBy(() -> loop.run(ctx, "go", finishingStrategy()))
                .isSameAs(serializationFailure);
        verify(aiClient).chatWithTools(anyList(), anyString(), eq(List.of()), anyString(), isNull(), anyInt());
    }

    @Test
    void run_observesOnlySafeReturnedTurnMetadata() {
        AgentLoop loop = new AgentLoop(aiClient, sessionService,
                new AgentBudget(2, 1, 0, 1024, 8000, 120_000, 200_000, 0.7));
        List<AgentRunContext.RoundObservation> observations = new ArrayList<>();
        ctx.setRoundObserver(observations::add);
        when(aiClient.chatWithTools(anyList(), anyString(), eq(List.of()), anyString(), isNull(), anyInt()))
                .thenReturn(new ChatTurn("safe response", List.of(), StopReason.END_TURN, 30, 7));

        loop.run(ctx, "go", finishingStrategy());

        assertThat(observations).containsExactly(new AgentRunContext.RoundObservation(
                1, ToolingMode.LEGACY, StopReason.END_TURN, 7L, 1024, 5, 13));
    }

    @Test
    void run_providerFailureObserverRunsAtFinalProviderBoundary() {
        AgentLoop loop = new AgentLoop(aiClient, sessionService,
                new AgentBudget(2, 1, 0, 1024, 8000, 120_000, 200_000, 0.7));
        AtomicInteger failures = new AtomicInteger();
        ctx.setProviderCallFailureObserver(failures::incrementAndGet);
        HttpMessageNotWritableException failure = new HttpMessageNotWritableException("provider failed");
        when(aiClient.chatWithTools(anyList(), anyString(), eq(List.of()), anyString(), isNull(), anyInt()))
                .thenThrow(failure);

        assertThatThrownBy(() -> loop.run(ctx, "go", finishingStrategy())).isSameAs(failure);

        assertThat(failures).hasValue(1);
    }

    @Test
    void run_providerFailureAfterRetryNotifiesOnceWithoutChangingRetryCount() {
        AgentLoop loop = new AgentLoop(aiClient, sessionService,
                new AgentBudget(2, 1, 0, 1024, 8000, 120_000, 200_000, 0.7));
        AtomicInteger failures = new AtomicInteger();
        ctx.setProviderCallFailureObserver(failures::incrementAndGet);
        ResourceAccessException first = new ResourceAccessException("first", new SocketException("Connection reset"));
        ResourceAccessException second = new ResourceAccessException("second", new SocketException("Connection reset"));
        when(aiClient.chatWithTools(anyList(), anyString(), eq(List.of()), anyString(), isNull(), anyInt()))
                .thenThrow(first).thenThrow(second);

        assertThatThrownBy(() -> loop.run(ctx, "go", finishingStrategy())).isSameAs(second);

        assertThat(failures).hasValue(1);
        verify(aiClient, times(2)).chatWithTools(anyList(), anyString(), eq(List.of()),
                anyString(), isNull(), anyInt());
    }

    @Test
    void run_roundObserverFailureCannotChangeTheStrategyOutcome() {
        AgentLoop loop = new AgentLoop(aiClient, sessionService,
                new AgentBudget(2, 1, 0, 1024, 8000, 120_000, 200_000, 0.7));
        ctx.setRoundObserver(observation -> {
            throw new IllegalStateException("observer failure");
        });
        when(aiClient.chatWithTools(anyList(), anyString(), eq(List.of()), anyString(), isNull(), anyInt()))
                .thenReturn(ChatTurn.text("done"));

        LoopOutcome outcome = loop.run(ctx, "go", finishingStrategy());

        assertThat(outcome.success()).isTrue();
    }

    @Test
    void transientNetworkFailure_requiresKnownNetworkFailure() {
        assertThat(AgentLoop.isTransientNetworkFailure(
                new RuntimeException(new SocketException("Connection reset by peer")))).isTrue();
        assertThat(AgentLoop.isTransientNetworkFailure(
                new RuntimeException(new SocketException("Permission denied")))).isFalse();
        assertThat(AgentLoop.isTransientNetworkFailure(
                new RuntimeException("broken pipe"))).isFalse();
    }

    @Test
    void transientNetworkFailure_streamingTransportFailuresAreRetried() {
        // A mid-stream read timeout (per-chunk stall detector) and a malformed
        // stream line both surface as ResourceAccessException and must be
        // retried, matching the streaming clients' contract.
        assertThat(AgentLoop.isTransientNetworkFailure(
                new ResourceAccessException("I/O error on POST request to \"/api/chat\": Read timed out",
                        new java.net.SocketTimeoutException("Read timed out")))).isTrue();
        assertThat(AgentLoop.isTransientNetworkFailure(
                new ResourceAccessException("Malformed Ollama stream line: Unexpected token"))).isTrue();
        assertThat(AgentLoop.isTransientNetworkFailure(
                new ResourceAccessException("I/O error on POST request to \"/completion\": Connection reset",
                        new java.net.SocketException("Connection reset")))).isTrue();
    }

    @Test
    void run_preparationBoundsInitialRequestAndReplaysActualCurrentWithoutPostResponseCompaction() {
        AgentBudget budget = new AgentBudget(2, 1, 0, 32, 4, 1, 2048, 0.7);
        doCallRealMethod().when(aiClient).estimateRequestTokens(anyList(), anyString(), anyList(), anyString());
        when(sessionService.toAiMessages(ctx.session())).thenReturn(List.of(
                AiMessage.builder().role("user").content("old".repeat(1000)).build()));
        List<List<AiMessage>> requests = new ArrayList<>();
        List<String> currents = new ArrayList<>();
        List<AgentRunContext.RoundObservation> observations = new ArrayList<>();
        ctx.setRoundObserver(observations::add);
        when(aiClient.chatWithTools(anyList(), anyString(), anyList(), anyString(), isNull(), anyInt()))
                .thenAnswer(inv -> {
                    requests.add(List.copyOf(inv.getArgument(0)));
                    currents.add(inv.getArgument(1));
                    return new ChatTurn("answer", List.of(), StopReason.END_TURN, 2000, 7);
                });
        String evidence = "evidence".repeat(40);
        AgentStrategy strategy = new PreparingStrategy() {
            @Override
            public String prepareRequest(AgentRunContext c, List<AiMessage> history, String current,
                                         String system, List<ToolDescriptor> tools, AgentBudget b,
                                         AiClient client, boolean aggressive) {
                assertThat(b).isSameAs(budget);
                assertThat(client).isSameAs(aiClient);
                assertThat(system).isEqualTo("sys");
                assertThat(tools).isEmpty();
                assertThat(aggressive).isFalse();
                if (currents.isEmpty()) {
                    history.clear();
                    history.add(AiMessage.builder().role("tool").content(evidence).build());
                }
                return current.substring(0, Math.min(12, current.length()));
            }
            @Override
            public StepDecision step(AgentRunContext c, String response, int round) {
                return round == 1 ? new StepDecision.Continue("next".repeat(1000))
                        : super.step(c, response, round);
            }
        };

        new AgentLoop(aiClient, sessionService, budget).run(ctx, "task".repeat(1000), strategy);

        assertThat(currents).containsExactly("tasktasktask", "nextnextnext");
        assertThat(requests.getFirst()).extracting(AiMessage::getContent).containsExactly(evidence);
        assertThat(requests.get(1)).extracting(AiMessage::getContent)
                .containsExactly(evidence, "tasktasktask", "answer");
        assertThat(observations.getFirst().promptCharacterEstimate()).isEqualTo(evidence.length() + 12 + 3);
        for (int i = 0; i < requests.size(); i++) {
            assertThat(aiClient.estimateRequestTokens(requests.get(i), currents.get(i), List.of(), "sys")
                    + budget.maxTokensPerCall())
                    .isLessThanOrEqualTo((long) (budget.contextWindowTokens() * budget.proactiveCompactionThreshold()));
        }
        verify(sessionService, never()).truncateToolMessages(anyLong(), anyInt());
    }

    @Test
    void run_promptTooLongRetryRepreparesAggressivelyAndUsesSuccessfulCurrentInHistory() {
        AgentBudget budget = new AgentBudget(2, 1, 0, 32, 4, 1, 2048, 0.7);
        HttpClientErrorException rejection = HttpClientErrorException.create(HttpStatus.BAD_REQUEST,
                "Bad Request", HttpHeaders.EMPTY, "prompt is too long".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
        when(aiClient.isPromptTooLongError(rejection)).thenReturn(true);
        List<Boolean> aggressiveFlags = new ArrayList<>();
        List<String> currents = new ArrayList<>();
        List<List<AiMessage>> requests = new ArrayList<>();
        when(aiClient.chatWithTools(anyList(), anyString(), anyList(), anyString(), isNull(), anyInt()))
                .thenAnswer(inv -> {
                    currents.add(inv.getArgument(1));
                    requests.add(List.copyOf(inv.getArgument(0)));
                    if (currents.size() == 1) {
                        throw rejection;
                    }
                    return ChatTurn.text("answer");
                });
        AgentStrategy strategy = new PreparingStrategy() {
            @Override
            public String prepareRequest(AgentRunContext c, List<AiMessage> history, String current,
                                         String system, List<ToolDescriptor> tools, AgentBudget b,
                                         AiClient client, boolean aggressive) {
                aggressiveFlags.add(aggressive);
                if (aggressive) {
                    assertThat(current).isEqualTo("prepared");
                    // Generic aggressive compaction must not have removed this evidence.
                    assertThat(history).hasSize(6);
                    history.clear();
                    history.add(AiMessage.builder().role("user").content("retained").build());
                    return "smaller";
                }
                if (currents.isEmpty()) {
                    for (int i = 0; i < 6; i++) {
                        history.add(AiMessage.builder().role("user").content("evidence-" + i).build());
                    }
                    return "prepared";
                }
                return current;
            }
            @Override
            public StepDecision step(AgentRunContext c, String response, int round) {
                return round == 1 ? new StepDecision.Continue("next") : super.step(c, response, round);
            }
        };

        new AgentLoop(aiClient, sessionService, budget).run(ctx, "original", strategy);

        assertThat(aggressiveFlags).containsExactly(false, true, false);
        assertThat(currents).containsExactly("prepared", "smaller", "next");
        assertThat(requests.get(1)).extracting(AiMessage::getContent).containsExactly("retained");
        assertThat(requests.get(2)).extracting(AiMessage::getContent)
                .containsExactly("retained", "smaller", "answer");
    }

    @Test
    void run_preparesAgainForNetworkRetryWithoutAggressiveFlag() {
        List<Boolean> flags = new ArrayList<>();
        when(aiClient.chatWithTools(anyList(), anyString(), anyList(), anyString(), isNull(), anyInt()))
                .thenThrow(new ResourceAccessException("Connection reset"))
                .thenReturn(ChatTurn.text("answer"));
        AgentStrategy strategy = new PreparingStrategy() {
            @Override
            public String prepareRequest(AgentRunContext c, List<AiMessage> history, String current,
                                         String system, List<ToolDescriptor> tools, AgentBudget b,
                                         AiClient client, boolean aggressive) {
                flags.add(aggressive);
                return "prepared";
            }
        };

        new AgentLoop(aiClient, sessionService,
                new AgentBudget(1, 1, 0, 32, 4, 1000, 2048, 0.7)).run(ctx, "go", strategy);

        assertThat(flags).containsExactly(false, false);
        verify(aiClient, times(2)).chatWithTools(anyList(), eq("prepared"), anyList(),
                eq("sys"), isNull(), eq(32));
    }

    @Test
    void run_promptTooLongWithPreparationStillStopsAfterTwoProviderAttempts() {
        HttpClientErrorException rejection = HttpClientErrorException.create(HttpStatus.BAD_REQUEST,
                "Bad Request", HttpHeaders.EMPTY, "prompt is too long".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
        when(aiClient.isPromptTooLongError(rejection)).thenReturn(true);
        when(aiClient.chatWithTools(anyList(), anyString(), anyList(), anyString(), isNull(), anyInt()))
                .thenThrow(rejection);
        List<Boolean> flags = new ArrayList<>();
        AgentStrategy strategy = new PreparingStrategy() {
            @Override
            public String prepareRequest(AgentRunContext c, List<AiMessage> history, String current,
                                         String system, List<ToolDescriptor> tools, AgentBudget b,
                                         AiClient client, boolean aggressive) {
                flags.add(aggressive);
                return current;
            }
        };

        assertThatThrownBy(() -> new AgentLoop(aiClient, sessionService,
                new AgentBudget(1, 1, 0, 32, 4, 1000, 2048, 0.7)).run(ctx, "go", strategy))
                .isSameAs(rejection);

        assertThat(flags).containsExactly(false, true);
        verify(aiClient, times(2)).chatWithTools(anyList(), eq("go"), anyList(), eq("sys"), isNull(), eq(32));
    }

    @Test
    void run_defaultLegacyPromptTooLongRetainsExistingAggressiveCompactor() {
        List<AiMessage> history = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            history.add(AiMessage.builder().role("user").content("evidence-" + i).build());
        }
        when(sessionService.toAiMessages(ctx.session())).thenReturn(history);
        HttpClientErrorException rejection = HttpClientErrorException.create(HttpStatus.BAD_REQUEST,
                "Bad Request", HttpHeaders.EMPTY, "prompt is too long".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
        when(aiClient.isPromptTooLongError(rejection)).thenReturn(true);
        List<List<AiMessage>> requests = new ArrayList<>();
        when(aiClient.chatWithTools(anyList(), anyString(), anyList(), anyString(), isNull(), anyInt()))
                .thenAnswer(inv -> {
                    requests.add(List.copyOf(inv.getArgument(0)));
                    if (requests.size() == 1) {
                        throw rejection;
                    }
                    return ChatTurn.text("done");
                });

        new AgentLoop(aiClient, sessionService,
                new AgentBudget(1, 1, 0, 32, 4, 1, 2048, 0.7)).run(ctx, "go", finishingStrategy());

        assertThat(requests.getFirst()).hasSize(6);
        assertThat(requests.get(1)).hasSize(3);
        assertThat(requests.get(1).getFirst().getContent()).contains("aggressively compacted");
        assertThat(requests.get(1).subList(1, 3)).extracting(AiMessage::getContent)
                .containsExactly("evidence-4", "evidence-5");
    }

    @Test
    void run_mandatoryOnlyOverflowFailsBeforeProviderWithoutProviderFailureObservation() {
        AtomicInteger providerFailures = new AtomicInteger();
        ctx.setProviderCallFailureObserver(providerFailures::incrementAndGet);
        when(aiClient.estimateRequestTokens(anyList(), anyString(), anyList(), anyString())).thenReturn(701L);
        ContextBudgetExceededException failure = new ContextBudgetExceededException("Mandatory context cannot fit");
        AgentStrategy strategy = new PreparingStrategy() {
            @Override
            public String prepareRequest(AgentRunContext c, List<AiMessage> history, String current,
                                         String system, List<ToolDescriptor> tools, AgentBudget b,
                                         AiClient client, boolean aggressive) {
                long input = client.estimateRequestTokens(history, current, tools, system);
                if (input + b.maxTokensPerCall() > b.contextWindowTokens() * b.proactiveCompactionThreshold()) {
                    throw failure;
                }
                return current;
            }
        };

        assertThatThrownBy(() -> new AgentLoop(aiClient, sessionService,
                new AgentBudget(1, 1, 0, 100, 4, 1000, 1000, 0.8)).run(ctx, "mandatory", strategy))
                .isSameAs(failure);

        verify(aiClient, never()).chatWithTools(anyList(), anyString(), anyList(), anyString(), any(), any());
        assertThat(providerFailures).hasValue(0);
        assertThat(AiAuditContext.getRound()).isNull();
    }

    @Test
    void run_requestPreparationIsDefaultOffForLegacyStrategy() {
        AgentStrategy strategy = new PreparingStrategy() {
            @Override public boolean usesRequestPreparation() { return false; }
            @Override
            public String prepareRequest(AgentRunContext c, List<AiMessage> history, String current,
                                         String system, List<ToolDescriptor> tools, AgentBudget b,
                                         AiClient client, boolean aggressive) {
                throw new AssertionError("Preparation must remain opt-in");
            }
        };
        assertThat(finishingStrategy().usesRequestPreparation()).isFalse();
        assertThat(finishingStrategy().prepareRequest(ctx, new ArrayList<>(), "original", "sys",
                List.of(), null, aiClient, false)).isEqualTo("original");
        when(aiClient.chatWithTools(anyList(), eq("original"), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(ChatTurn.text("answer"));

        new AgentLoop(aiClient, sessionService,
                new AgentBudget(1, 1, 0, 32, 4, 1000, 2048, 0.7)).run(ctx, "original", strategy);

        verify(aiClient).chatWithTools(anyList(), eq("original"), eq(List.of()), eq("sys"), isNull(), eq(32));
    }

    @Test
    void run_nativeModeNeverInvokesOptInRequestPreparation() {
        List<ToolDescriptor> tools = List.of(new ToolDescriptor("read", "Read source", null));
        when(aiClient.supportsNativeTools()).thenReturn(true);
        when(aiClient.chatWithTools(anyList(), eq("original"), eq(tools), anyString(), isNull(), anyInt()))
                .thenReturn(ChatTurn.text("answer"));
        AgentStrategy strategy = new PreparingStrategy() {
            @Override public ToolingMode preferredToolMode() { return ToolingMode.NATIVE; }
            @Override public List<ToolDescriptor> toolDescriptors() { return tools; }
            @Override
            public String prepareRequest(AgentRunContext c, List<AiMessage> history, String current,
                                         String system, List<ToolDescriptor> descriptors, AgentBudget b,
                                         AiClient client, boolean aggressive) {
                throw new AssertionError("Native preparation is not supported");
            }
        };

        new AgentLoop(aiClient, sessionService,
                new AgentBudget(1, 1, 0, 32, 4, 1000, 2048, 0.7)).run(ctx, "original", strategy);

        assertThat(ctx.toolingMode()).isEqualTo(ToolingMode.NATIVE);
    }

    private static class PreparingStrategy implements AgentStrategy {
        @Override public String systemPrompt() { return "sys"; }
        @Override public boolean usesRequestPreparation() { return true; }
        @Override public StepDecision step(AgentRunContext c, String response, int round) {
            return new StepDecision.Finish(LoopOutcome.success(c.baseBranch(), response));
        }
        @Override public LoopOutcome onBudgetExhausted(AgentRunContext c) {
            throw new AssertionError("budget should not be exhausted");
        }
    }

    private static AgentStrategy finishingStrategy() {
        return new AgentStrategy() {
            @Override public String systemPrompt() { return "sys"; }
            @Override public StepDecision step(AgentRunContext c, String r, int round) {
                return new StepDecision.Finish(LoopOutcome.success(c.baseBranch(), r));
            }
            @Override public LoopOutcome onBudgetExhausted(AgentRunContext c) {
                throw new AssertionError("budget should not be exhausted");
            }
        };
    }
}
