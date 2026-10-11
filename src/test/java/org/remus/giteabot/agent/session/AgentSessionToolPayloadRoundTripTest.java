package org.remus.giteabot.agent.session;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.remus.giteabot.agent.shared.AgentJackson;
import org.remus.giteabot.ai.AiMessage;
import org.remus.giteabot.ai.ToolCall;
import org.remus.giteabot.session.ConversationMessage;

import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A native tool exchange must survive the run boundary: persisted with its
 * {@code tool_calls} / {@code tool_call_id} payload, and rebuilt as a valid
 * assistant/tool pair by {@link AgentSessionService#toAiMessages} — otherwise a
 * follow-up run would send an orphaned tool message, which providers reject.
 */
@SpringBootTest
@ActiveProfiles("test")
class AgentSessionToolPayloadRoundTripTest {

    @Autowired private AgentSessionRepository repository;
    @Autowired private AgentSessionService service;
    @Autowired private TransactionTemplate tx;

    @Test
    void toolExchangeSurvivesThePersistenceRoundTrip() {
        ToolCall call = new ToolCall("call_1", "cat",
                AgentJackson.mapper().readTree("{\"path\":\"README.md\"}"), null);

        Long id = tx.execute(s -> service.createSession("owner", "repo", 4_242L, "payload round trip").getId());
        tx.executeWithoutResult(s -> service.flushMessages(id, List.of(
                new PendingMessage("user", "go"),
                new PendingMessage("assistant", "reading the readme",
                        new PendingMessage.ToolPayload(List.of(call), null)),
                new PendingMessage("tool", "[call_1] README body",
                        new PendingMessage.ToolPayload(null, "call_1"))), 10L, 20L));

        AgentSession reloaded = tx.execute(s -> {
            AgentSession session = repository.findById(id).orElseThrow();
            session.getMessages().size(); // force-initialise within the transaction
            return session;
        });
        List<ConversationMessage> stored = reloaded.getMessages().stream()
                .sorted(Comparator.comparing(ConversationMessage::getCreatedAt))
                .toList();
        assertThat(stored).extracting(ConversationMessage::getRole)
                .containsExactly("user", "assistant", "tool");
        assertThat(stored.get(1).getToolCalls()).contains("call_1").contains("cat").contains("README.md");
        assertThat(stored.get(1).getToolCallId()).isNull();
        assertThat(stored.get(2).getToolCallId()).isEqualTo("call_1");
        // The row keeps its review marker in storage; only the replay strips it.
        assertThat(stored.get(2).getContent()).isEqualTo("[call_1] README body");

        List<AiMessage> replay = service.toAiMessages(reloaded);
        assertThat(replay).extracting(AiMessage::getRole).containsExactly("user", "assistant", "tool");
        assertThat(replay.get(1).getToolCalls()).extracting(ToolCall::id).containsExactly("call_1");
        assertThat(replay.get(1).getToolCalls().get(0).args().get("path").asString()).isEqualTo("README.md");
        assertThat(replay.get(2).getToolCallId()).isEqualTo("call_1");
        assertThat(replay.get(2).getToolResult()).isEqualTo("README body");
    }

    @Test
    void aTurnWhoseCallsWereNeverAnsweredIsNotReplayedWithItsPayload() {
        // The give-up path: the run finishes on a tool-call turn, so the session keeps the
        // payload without a single tool row while the comment tells the user to mention the
        // bot again. The follow-up run must still send a request the provider accepts.
        ToolCall call = new ToolCall("cat:0", "cat",
                AgentJackson.mapper().readTree("{\"path\":\"README.md\"}"), null);

        Long id = tx.execute(s -> service.createSession("owner", "repo", 4_243L, "give-up round trip").getId());
        tx.executeWithoutResult(s -> service.flushMessages(id, List.of(
                new PendingMessage("user", "tighten this issue"),
                new PendingMessage("assistant", "", new PendingMessage.ToolPayload(List.of(call), null))),
                10L, 20L));

        AgentSession reloaded = tx.execute(s -> {
            AgentSession session = repository.findById(id).orElseThrow();
            session.getMessages().size(); // force-initialise within the transaction
            return session;
        });

        List<AiMessage> replay = service.toAiMessages(reloaded);

        // Either every announced call has a result behind it, or the call travels without
        // its payload — never an assistant turn whose calls go unanswered.
        assertThat(replay).extracting(AiMessage::getRole).containsExactly("user");
        assertThat(replay).allSatisfy(message -> assertThat(message.getToolCalls()).isNullOrEmpty());
    }
}
