package org.remus.giteabot.bitbucket.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import org.remus.giteabot.repository.model.ReviewComment;

/**
 * Bitbucket Cloud implementation of {@link ReviewComment}.
 * API response model for a Bitbucket pull request comment.
 * Returned by GET /repositories/{workspace}/{repo}/pullrequests/{pr_id}/comments
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BitbucketReviewComment implements ReviewComment {

    private Long id;

    @JsonProperty("content")
    private BitbucketContent content;

    private String path;

    @JsonProperty("diff_hunk")
    private String diffHunk;

    private Integer line;

    private BitbucketParent parent;

    private String threadId;

    @JsonProperty("created_on")
    private String createdAt;

    private BitbucketReview.BitbucketUser user;

    @Override
    public String getThreadId() {
        return threadId != null ? threadId : parent != null && parent.getId() != null ? parent.getId().toString()
                : id == null ? null : id.toString();
    }

    @Override
    public String getParentId() {
        return parent == null || parent.getId() == null ? null : parent.getId().toString();
    }

    @Override
    public String getCreatedAt() {
        return createdAt;
    }

    @Override
    public String getBody() {
        return content != null ? content.getRaw() : null;
    }

    @Override
    public String getUserLogin() {
        return user != null ? (user.getNickname() == null ? user.getDisplayName() : user.getNickname()) : null;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BitbucketContent {
        private String raw;
        private String markup;
        private String html;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BitbucketParent {
        private Long id;
    }
}
