package org.remus.giteabot.ai;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.agent.shared.AgentJackson;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

class AiClientRequestSizingTest {

    private final AiClient client = mock(AiClient.class, CALLS_REAL_METHODS);

    @Test
    void defaultEstimateIncludesMessagesToolsReasoningAndSerializedUtf8Escaping() {
        long empty = client.estimateRequestTokens(List.of(), "", List.of(), "");
        long ascii = client.estimateRequestTokens(List.of(), "a".repeat(100), List.of(), "");
        long unicode = client.estimateRequestTokens(List.of(), "界".repeat(100), List.of(), "");
        long escaped = client.estimateRequestTokens(List.of(), "\n".repeat(100), List.of(), "");
        AiMessage history = AiMessage.builder().role("assistant").content("history")
                .toolCallId("call-id").toolResult("result")
                .toolCalls(List.of(new ToolCall("id", "read",
                        AgentJackson.mapper().readTree("{\"file\":\"source.java\"}")))).build();
        List<ToolDescriptor> tools = List.of(new ToolDescriptor("read", "Read source",
                AgentJackson.mapper().readTree("{\"type\":\"object\"}")));
        long full = client.estimateRequestTokens(List.of(history), "current", tools, "system");
        history.setReasoningDetails(List.of(AgentJackson.mapper().readTree(
                "{\"opaque\":\"" + "reasoning".repeat(100) + "\"}")));
        long reasoning = client.estimateRequestTokens(List.of(history), "current", tools, "system");

        assertThat(empty).isGreaterThan(256);
        assertThat(ascii).isGreaterThan(empty);
        assertThat(unicode).isGreaterThan(ascii);
        assertThat(escaped).isGreaterThan(ascii);
        assertThat(full).isGreaterThan(client.estimateRequestTokens(List.of(), "current", List.of(), "system"));
        assertThat(reasoning).isGreaterThan(full);
    }

    @Test
    void bytePolicyAddsMarginAndDoesNotClaimExactTokensOrIncludeReservedOutput() {
        String request = "界\n\"";
        long bytes = AgentJackson.mapper().writeValueAsBytes(request).length;

        assertThat(AiClient.estimateSerializedRequestTokens(request))
                .isEqualTo(bytes + (bytes + 3) / 4 + 256);
        assertThat(client.estimateRequestTokens(null, null, null, null))
                .isEqualTo(client.estimateRequestTokens(List.of(), "", List.of(), ""));
    }
}
