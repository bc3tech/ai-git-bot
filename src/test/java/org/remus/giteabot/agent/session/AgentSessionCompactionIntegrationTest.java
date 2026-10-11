package org.remus.giteabot.agent.session;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.ai.AiMessage;
import org.remus.giteabot.ai.ToolCall;
import org.remus.giteabot.session.ConversationMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end persistence test for the AgentSession compaction + batch-flush
 * lifecycle, exercised across real (committing) transaction boundaries so the
 * entities detach between steps — exactly the webhook flow's behaviour.
 *
 * <p>Reproduces the sequence behind the {@code ObjectNotFoundException} fixed in
 * commit 71632d7: a follow-up run compacts a session whose history was already
 * trimmed by a previous compaction, then flushes new messages. Because every
 * write now goes through a freshly-fetched managed entity (never a {@code merge}
 * of the caller's detached object), the deleted rows are never re-resolved and
 * the cycle completes cleanly.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class AgentSessionCompactionIntegrationTest {

    @Autowired private AgentSessionRepository repository;
    @Autowired private AgentSessionService service;
    @Autowired private TransactionTemplate tx;

    private List<PendingMessage> bigBatch(int count) {
        String big = "x".repeat(10_000); // 10k chars each -> 10 messages exceed the 80k threshold
        List<PendingMessage> batch = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            batch.add(new PendingMessage(i % 2 == 0 ? "user" : "assistant", big));
        }
        return batch;
    }

    @Test
    void compactFlushCompactCycle_acrossTransactions_doesNotThrowAndKeepsHistoryBounded() {
        // tx1 — create the session (committed, then detached).
        Long id = tx.execute(s -> service.createSession(
                "owner", "repo", 9_000_001L, "Big session").getId());
        assertThat(id).isNotNull();

        // tx2 — first run persists a large history (>8 messages, >80k chars).
        tx.executeWithoutResult(s -> service.flushMessages(id, bigBatch(10), 1000L, 500L));

        // tx3 — follow-up run compacts: 8 most-recent retained + 1 summary message.
        AgentSession compacted = tx.execute(s -> service.compactContextWindow(id));
        assertThat(compacted.getMessages()).hasSize(9);

        // tx4 — that run flushes another large batch onto the compacted session.
        tx.executeWithoutResult(s -> service.flushMessages(id, bigBatch(10), 2000L, 900L));

        // tx5 — a third run compacts a session whose history was ALREADY trimmed
        // once. This is the step that previously threw ObjectNotFoundException
        // (merging a detached entity whose collection referenced deleted rows).
        AgentSession recompacted = tx.execute(s -> service.compactContextWindow(id));
        assertThat(recompacted.getMessages()).hasSize(9);

        // tx6 — flush the final round's messages.
        tx.executeWithoutResult(s -> service.flushMessages(id,
                List.of(new PendingMessage("user", "follow-up"),
                        new PendingMessage("assistant", "answer")),
                2500L, 1100L));

        // tx7 — reload from a fresh transaction and assert the committed state.
        AgentSession reloaded = tx.execute(s -> {
            AgentSession sess = repository.findById(id).orElseThrow();
            sess.getMessages().size(); // force-initialise within the transaction
            return sess;
        });
        assertThat(reloaded.getMessages()).hasSize(11); // 9 after compaction + 2 new
        assertThat(reloaded.getTotalInputTokens()).isEqualTo(2500L);
        assertThat(reloaded.getTotalOutputTokens()).isEqualTo(1100L);

        // Replay view is well-formed and ordered (no exception, no deleted-row refs).
        assertThat(service.toAiMessages(reloaded)).isNotEmpty();
    }

    @Test
    void compaction_neverKeepsAToolRowWithoutTheAssistantTurnThatAnnouncedIt() {
        Long id = tx.execute(s -> service.createSession(
                "owner", "repo", 9_000_002L, "Tool exchange session").getId());
        String big = "y".repeat(17_000); // five of these exceed the 80k compaction threshold
        ToolCall call = new ToolCall("call_1", "cat", null, null);

        // Ten messages whose 8-message window would start on the first tool row.
        tx.executeWithoutResult(s -> service.flushMessages(id, List.of(
                new PendingMessage("user", big),
                new PendingMessage("assistant", "reading",
                        new PendingMessage.ToolPayload(List.of(call), null)),
                new PendingMessage("tool", "[call_1] first body",
                        new PendingMessage.ToolPayload(null, "call_1")),
                new PendingMessage("tool", "[call_1] second body",
                        new PendingMessage.ToolPayload(null, "call_1")),
                new PendingMessage("assistant", big),
                new PendingMessage("user", big),
                new PendingMessage("assistant", big),
                new PendingMessage("user", big),
                new PendingMessage("assistant", big),
                new PendingMessage("user", big)), 0L, 0L));

        AgentSession compacted = tx.execute(s -> service.compactContextWindow(id));

        List<ConversationMessage> kept = compacted.getMessages().stream()
                .filter(message -> !message.getContent().startsWith("[Previous agent session context"))
                .sorted(Comparator.comparing(ConversationMessage::getCreatedAt))
                .toList();
        // The compaction ran (it replaced the removed prefix with a summary) ...
        assertThat(compacted.getMessages()).anySatisfy(message ->
                assertThat(message.getContent()).contains("compacted to save space"));
        // ... and the window was widened back onto the announcing assistant turn rather
        // than starting on the tool row, which would have kept a result without its call.
        assertThat(kept.get(0).getRole()).isEqualTo("assistant");
        assertThat(kept.get(0).getToolCalls()).contains("call_1");
        assertThat(kept).extracting(ConversationMessage::getRole).contains("tool");

        List<AiMessage> replay = service.toAiMessages(compacted);
        assertThat(replay).isNotEmpty();
        assertThat(replay.get(0).getRole()).isEqualTo("assistant");
        assertThat(replay.get(0).getToolCalls()).extracting(ToolCall::id).containsExactly("call_1");
        assertThat(replay.get(1).getRole()).isEqualTo("tool");
        assertThat(replay.get(1).getToolCallId()).isEqualTo("call_1");
    }
}
