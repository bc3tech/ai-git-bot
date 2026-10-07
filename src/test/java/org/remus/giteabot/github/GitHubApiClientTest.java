package org.remus.giteabot.github;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.repository.PostReviewAction;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.model.DiffSide;
import org.remus.giteabot.repository.model.RepositoryCredentials;
import org.remus.giteabot.repository.model.ReviewAnchorComment;
import org.remus.giteabot.repository.model.ReviewPublicationResult;
import org.remus.giteabot.repository.model.ReviewSnapshot;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * Unit tests for {@link GitHubApiClient} verifying that it correctly implements
 * {@link RepositoryApiClient} and exposes the expected base URL, clone URL, and token.
 */
class GitHubApiClientTest {

    private static final RepositoryCredentials CREDS =
            RepositoryCredentials.of("https://api.github.com", "https://github.com", "ghp_token");

    @Test
    void implementsRepositoryApiClient() {
        GitHubApiClient client = new GitHubApiClient(null, CREDS);
        assertInstanceOf(RepositoryApiClient.class, client);
    }

    @Test
    void getBaseUrl_returnsConfiguredUrl() {
        GitHubApiClient client = new GitHubApiClient(null, CREDS);
        assertEquals("https://api.github.com", client.getBaseUrl());
    }

    @Test
    void getCloneUrl_returnsConfiguredUrl() {
        GitHubApiClient client = new GitHubApiClient(null, CREDS);
        assertEquals("https://github.com", client.getCloneUrl());
    }

    @Test
    void getToken_returnsConfiguredToken() {
        GitHubApiClient client = new GitHubApiClient(null, CREDS);
        assertEquals("ghp_token", client.getToken());
    }

    @Test
    void constructorWithEnterpriseUrl() {
        var enterpriseCreds = RepositoryCredentials.of(
                "https://github.example.com/api/v3", "https://github.example.com", "token123");
        GitHubApiClient client = new GitHubApiClient(null, enterpriseCreds);
        assertEquals("https://github.example.com/api/v3", client.getBaseUrl());
        assertEquals("https://github.example.com", client.getCloneUrl());
    }

    @Test
    void getIssueComments_fetchesIssueCommentsWithPageLimit() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubApiClient client = new GitHubApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://api.github.com/repos/owner/repo/issues/42/comments?per_page=50"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[{\"id\":101,\"body\":\"First comment\"}]", MediaType.APPLICATION_JSON));

        List<Map<String, Object>> comments = client.getIssueComments("owner", "repo", 42L);

        server.verify();
        assertEquals(1, comments.size());
        assertEquals(101, ((Number) comments.getFirst().get("id")).intValue());
        assertEquals("First comment", comments.getFirst().get("body"));
    }

    @Test
    void addPullRequestReaction_postsEyesToIssueReactionEndpoint() {
        RestClient.Builder builder = RestClient.builder().baseUrl(CREDS.baseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubApiClient client = new GitHubApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://api.github.com/repos/owner/repo/issues/42/reactions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.content").value("eyes"))
                .andRespond(withSuccess());

        client.addPullRequestReaction("owner", "repo", 42L, "eyes");

        server.verify();
    }

    @Test
    void addIssueReaction_postsEyesToIssueReactionEndpoint() {
        RestClient.Builder builder = RestClient.builder().baseUrl(CREDS.baseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubApiClient client = new GitHubApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://api.github.com/repos/owner/repo/issues/12/reactions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.content").value("eyes"))
                .andRespond(withSuccess());

        client.addIssueReaction("owner", "repo", 12L, "eyes");

        server.verify();
    }

    @Test
    void postReview_requestChanges_submitsSingleReviewWithBodyAndEvent() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubApiClient client = new GitHubApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://api.github.com/repos/owner/repo/pulls/7/reviews"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.body").value("The findings"))
                .andExpect(jsonPath("$.event").value("REQUEST_CHANGES"))
                .andRespond(withSuccess());

        client.postReview("owner", "repo", 7L, "The findings", PostReviewAction.REQUEST_CHANGES);

        server.verify();
    }

    @Test
    void postReview_none_submitsSingleCommentReview() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubApiClient client = new GitHubApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://api.github.com/repos/owner/repo/pulls/7/reviews"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.body").value("Just a comment"))
                .andExpect(jsonPath("$.event").value("COMMENT"))
                .andRespond(withSuccess());

        client.postReview("owner", "repo", 7L, "Just a comment", PostReviewAction.NONE);

        server.verify();
    }

    @Test
    void assignIssue_postsAssignees() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubApiClient client = new GitHubApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://api.github.com/repos/owner/repo/issues/42/assignees"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.assignees[0]").value("alice"))
                .andRespond(withSuccess("{\"number\":42}", MediaType.APPLICATION_JSON));

        client.assignIssue("owner", "repo", 42L, "alice");

        server.verify();
    }

    @Test
    void getReviewSnapshot_retriesChangedHeadAndReturnsConsistentSnapshot() {
        RestClient.Builder builder = RestClient.builder().baseUrl(CREDS.baseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubApiClient client = new GitHubApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://api.github.com/repos/owner/repo/pulls/7"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"head\":{\"sha\":\"old-head\"},\"base\":{\"sha\":\"base\"}}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.com/repos/owner/repo/pulls/7"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("diff --git a/a b/a\n", MediaType.TEXT_PLAIN));
        server.expect(requestTo("https://api.github.com/repos/owner/repo/pulls/7"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"head\":{\"sha\":\"new-head\"},\"base\":{\"sha\":\"base\"}}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.com/repos/owner/repo/pulls/7"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"head\":{\"sha\":\"new-head\"},\"base\":{\"sha\":\"base\"}}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.com/repos/owner/repo/pulls/7"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("diff --git a/a b/a\n", MediaType.TEXT_PLAIN));
        server.expect(requestTo("https://api.github.com/repos/owner/repo/pulls/7"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"head\":{\"sha\":\"new-head\"},\"base\":{\"sha\":\"base\"}}",
                        MediaType.APPLICATION_JSON));

        ReviewSnapshot snapshot = client.getReviewSnapshot("owner", "repo", 7L);

        server.verify();
        assertEquals("new-head", snapshot.headSha());
        assertEquals("base", snapshot.baseSha());
        assertTrue(snapshot.inlineSupported());
        assertEquals("diff --git a/a b/a\n", snapshot.diff());
    }

    @Test
    void publishInlineReview_sendsCommitBoundCommentsAndMapsConfirmation() {
        RestClient.Builder builder = RestClient.builder().baseUrl(CREDS.baseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubApiClient client = new GitHubApiClient(builder.build(), CREDS);
        ReviewSnapshot snapshot = new ReviewSnapshot("head-sha", "base-sha", null, "diff", true);
        ReviewAnchorComment comment = new ReviewAnchorComment("finding-1", "src/A.java", "src/A.java",
                DiffSide.NEW, 12, null, 12, "Fix this line");

        server.expect(requestTo("https://api.github.com/repos/owner/repo/pulls/7/reviews"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.commit_id").value("head-sha"))
                .andExpect(jsonPath("$.body").value("Summary"))
                .andExpect(jsonPath("$.event").value("REQUEST_CHANGES"))
                .andExpect(jsonPath("$.comments[0].path").value("src/A.java"))
                .andExpect(jsonPath("$.comments[0].line").value(12))
                .andExpect(jsonPath("$.comments[0].side").value("RIGHT"))
                .andExpect(jsonPath("$.comments[0].position").doesNotExist())
                .andRespond(withSuccess("{\"id\":123}", MediaType.APPLICATION_JSON));

        ReviewPublicationResult result = client.publishInlineReview("owner", "repo", 7L, snapshot,
                "Summary", List.of(comment), PostReviewAction.REQUEST_CHANGES);

        server.verify();
        assertEquals(ReviewPublicationResult.Status.PUBLISHED, result.status());
        assertEquals(ReviewPublicationResult.Delivery.CONFIRMED, result.review());
        assertEquals(ReviewPublicationResult.Delivery.CONFIRMED, result.comments().getFirst().delivery());
    }

    @Test
    void publishInlineReview_returnsRejectedForClientError() {
        RestClient.Builder builder = RestClient.builder().baseUrl(CREDS.baseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubApiClient client = new GitHubApiClient(builder.build(), CREDS);
        ReviewAnchorComment comment = new ReviewAnchorComment("finding-1", "src/A.java", null,
                DiffSide.OLD, 4, 4, null, "Removed line");
        server.expect(requestTo("https://api.github.com/repos/owner/repo/pulls/7/reviews"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY));

        ReviewPublicationResult result = client.publishInlineReview("owner", "repo", 7L,
                new ReviewSnapshot("head", "base", null, "diff", true), "Summary",
                List.of(comment), PostReviewAction.NONE);

        server.verify();
        assertEquals(ReviewPublicationResult.Status.REJECTED, result.status());
        assertTrue(result.nothingWritten());
    }

    @Test
    void publishInlineReview_returnsUnknownForServerErrorWithoutRetrying() {
        RestClient.Builder builder = RestClient.builder().baseUrl(CREDS.baseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubApiClient client = new GitHubApiClient(builder.build(), CREDS);
        server.expect(requestTo("https://api.github.com/repos/owner/repo/pulls/7/reviews"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        ReviewPublicationResult result = client.publishInlineReview("owner", "repo", 7L,
                new ReviewSnapshot("head", "base", null, "diff", true), "Summary",
                List.of(), PostReviewAction.NONE);

        server.verify();
        assertEquals(ReviewPublicationResult.Status.UNKNOWN, result.status());
        assertFalse(result.nothingWritten());
    }
}
