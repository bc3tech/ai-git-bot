package org.remus.giteabot.github.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import org.remus.giteabot.repository.model.ReviewComment;

/**
 * GitHub-specific implementation of {@link ReviewComment}.
 * API response model for a GitHub pull request review comment.
 * Returned by GET /repos/{owner}/{repo}/pulls/{pull_number}/reviews/{review_id}/comments
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GitHubReviewComment implements ReviewComment {

    private Long id;

    private String body;

    private String path;

    @JsonProperty("diff_hunk")
    private String diffHunk;

    private Integer line;

    @JsonProperty("original_line")
    private Integer originalLine;

    @JsonProperty("in_reply_to_id")
    private Long inReplyToId;

    @JsonProperty("pull_request_review_id")
    private Long pullRequestReviewId;

    @JsonProperty("created_at")
    private String createdAt;

    private GitHubReview.GitHubUser user;

    @Override
    public String getThreadId() {
        Long rootId = inReplyToId == null ? id : inReplyToId;
        return rootId == null ? null : rootId.toString();
    }

    @Override
    public String getParentId() {
        return inReplyToId == null ? null : inReplyToId.toString();
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
