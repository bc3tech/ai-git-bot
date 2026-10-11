package org.remus.giteabot.review.state;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@Entity
@Table(name = "review_revision",
        uniqueConstraints = @UniqueConstraint(name = "uk_review_revision_scope",
                columnNames = {"bot_id", "repo_owner", "repo_name", "pr_number", "workflow_key"}),
        indexes = @Index(name = "idx_review_revision_closed", columnList = "pr_closed_at"))
public class ReviewRevisionRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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

    @Column(name = "head_sha", length = 128)
    private String headSha;

    @Column(name = "effective_base_sha", length = 128)
    private String effectiveBaseSha;

    @Column(name = "reviewed_at", nullable = false)
    private Instant reviewedAt;

    @Column(nullable = false)
    private boolean degraded;

    @Column(name = "last_seen_comment_at")
    private Instant lastSeenCommentAt;

    @Column(name = "last_seen_comment_id", length = 255)
    private String lastSeenCommentId;

    @Column(name = "pr_closed_at")
    private Instant prClosedAt;

    @PrePersist
    void prePersist() {
        if (reviewedAt == null) {
            reviewedAt = Instant.now();
        }
    }
}
