package org.remus.giteabot.review.state;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@Entity
@Table(name = "review_run_lease",
        uniqueConstraints = @UniqueConstraint(name = "uk_review_run_lease_scope",
                columnNames = {"bot_id", "repo_owner", "repo_name", "pr_number"}))
public class ReviewRunLease {

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

    @Column(name = "lease_owner", length = 36)
    private String leaseOwner;

    @Column(name = "lease_token", nullable = false)
    private long leaseToken;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;
}
