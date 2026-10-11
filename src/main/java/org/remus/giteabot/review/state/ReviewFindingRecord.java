package org.remus.giteabot.review.state;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@NoArgsConstructor
@Entity
@Table(name = "review_finding", indexes = {
        @Index(name = "idx_review_finding_scope_status",
                columnList = "bot_id, repo_owner, repo_name, pr_number, workflow_key, status")
})
public class ReviewFindingRecord {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 128)
    private String marker;

    @Column(name = "bot_id", nullable = false)
    private Long botId;

    @Column(name = "repo_owner", nullable = false)
    private String repoOwner;

    @Column(name = "repo_name", nullable = false)
    private String repoName;

    @Column(name = "pr_number", nullable = false)
    private Long prNumber;

    @Column(name = "workflow_key", nullable = false, length = 64)
    private String workflowKey;

    @Column(length = 2048)
    private String path;

    @Column(length = 16)
    private String side;

    private Integer line;

    @Column(columnDefinition = "TEXT")
    private String quote;

    @Column(name = "quote_hash", length = 64)
    private String quoteHash;

    @Column(name = "context_hash", length = 64)
    private String contextHash;

    @Column(length = 32)
    private String severity;

    @Column(length = 64)
    private String category;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReviewFindingStatus status = ReviewFindingStatus.PENDING;

    @Column(name = "summary_only", nullable = false)
    private boolean summaryOnly;

    @Column(name = "remote_comment_id", length = 255)
    private String remoteCommentId;

    @Column(name = "remote_thread_id", length = 255)
    private String remoteThreadId;

    @Column(name = "last_answered_comment_id", length = 255)
    private String lastAnsweredCommentId;

    @Column(name = "head_sha", length = 128)
    private String headSha;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (marker == null || marker.isBlank()) {
            marker = markerFor(id);
        }
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public static String markerFor(UUID id) {
        return "<!-- aigb:finding:" + id + " -->";
    }
}
