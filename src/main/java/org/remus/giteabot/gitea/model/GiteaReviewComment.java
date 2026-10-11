package org.remus.giteabot.gitea.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import org.remus.giteabot.repository.model.ReviewComment;

/**
 * Gitea-specific implementation of {@link ReviewComment}.
 * API response model for a Gitea pull request review comment.
 * Returned by GET /repos/{owner}/{repo}/pulls/{index}/reviews/{id}/comments
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GiteaReviewComment implements ReviewComment {

    private Long id;

    private String body;

    private String path;

    @JsonProperty("diff_hunk")
    private String diffHunk;

    private Integer line;

    @JsonProperty("old_line_num")
    private Integer oldLineNum;

    @JsonProperty("new_line_num")
    private Integer newLineNum;

    /** New-side file line as reported by Gitea's API ({@code PullReviewComment.position}). */
    private Integer position;

    @JsonProperty("pull_request_review_id")
    private Long reviewId;

    @JsonProperty("created_at")
    private String createdAt;

    /** Old-side file line as reported by Gitea's API ({@code PullReviewComment.original_position}). */
    @JsonProperty("original_position")
    private Integer originalPosition;

    private GiteaReview.GiteaUser user;

    @Override
    public String getThreadId() {
        return reviewId == null ? null : reviewId.toString();
    }

    @Override
    public String getCreatedAt() {
        return createdAt;
    }

    @Override
    public String getUserLogin() {
        return user != null ? user.getLogin() : null;
    }
}

