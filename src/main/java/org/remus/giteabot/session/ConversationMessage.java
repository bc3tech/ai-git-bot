package org.remus.giteabot.session;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@Entity
@Table(name = "conversation_messages")
public class ConversationMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String role;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /**
     * Native tool-call payload of an assistant turn (JSON array of
     * {@code {id,name,args,providerMetadata}} objects). {@code null} for turns
     * that called no tools, for every other role, and for rows persisted before
     * V53.
     */
    @Column(columnDefinition = "TEXT")
    private String toolCalls;

    /**
     * The tool call a {@code role:"tool"} row is the response to. {@code null}
     * for other roles and for rows persisted before V53.
     */
    @Column(name = "tool_call_id", length = 255)
    private String toolCallId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public ConversationMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }
}
