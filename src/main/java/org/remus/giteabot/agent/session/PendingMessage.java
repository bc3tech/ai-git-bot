package org.remus.giteabot.agent.session;

import org.remus.giteabot.ai.ToolCall;

import java.util.List;

/**
 * An in-flight conversation message that has not yet been persisted.
 *
 * <p>{@link org.remus.giteabot.agent.loop.AgentLoop} accumulates these during a
 * single round and hands the batch to
 * {@link AgentSessionService#flushMessages} so the round's messages are written
 * in one transaction instead of one transaction per message.</p>
 *
 * @param role    the message role (e.g. {@code "user"}, {@code "assistant"}, {@code "tool"})
 * @param content the message content
 * @param payload the native tool-call payload, or {@code null} when the message
 *                carries none
 */
public record PendingMessage(String role, String content, ToolPayload payload) {

    /** A message without a native tool-call payload (user text, assistant prose, ...). */
    public PendingMessage(String role, String content) {
        this(role, content, null);
    }

    /**
     * Native tool-call payload of a message: an assistant turn's {@code toolCalls}
     * (the provider's call ids, names and JSON args), or the {@code toolCallId} a
     * {@code role:"tool"} row is the response to. The other component is
     * {@code null}.
     */
    public record ToolPayload(List<ToolCall> toolCalls, String toolCallId) {
    }
}
