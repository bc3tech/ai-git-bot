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
    void removePullRequestReaction_deletesOnlyAuthenticatedBotsReaction() {
        RestClient.Builder builder = RestClient.builder().baseUrl(CREDS.baseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubApiClient client = new GitHubApiClient(builder.build(), CREDS);
        server.expect(requestTo(CREDS.baseUrl() + "/user"))
                .andRespond(withSuccess("{\"id\":1}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(CREDS.baseUrl() + "/repos/owner/repo/issues/7/reactions?per_page=100&page=1"))
                .andRespond(withSuccess("[{\"id\":10,\"content\":\"eyes\",\"user\":{\"id\":2}},{\"id\":11,\"content\":\"eyes\",\"user\":{\"id\":1}}]", MediaType.APPLICATION_JSON));
        server.expect(requestTo(CREDS.baseUrl() + "/repos/owner/repo/issues/7/reactions/11"))
                .andExpect(method(HttpMethod.DELETE)).andRespond(withSuccess());
        client.removePullRequestReaction("owner", "repo", 7L, "eyes");
        server.verify();
    }

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

        server.expect(requestTo("https://api.github.com/repos/owner/repo/issues/42/comments?per_page=100&page=1"))
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
    void getReviewSnapshot_missingMergeBaseDoesNotPinToTargetTip() {
        RestClient.Builder builder = RestClient.builder().baseUrl(CREDS.baseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubApiClient client = new GitHubApiClient(builder.build(), CREDS);
        String details = "{\"head\":{\"sha\":\"head\"},\"base\":{\"sha\":\"base\"}}";
        server.expect(requestTo(CREDS.baseUrl() + "/repos/owner/repo/pulls/7"))
                .andRespond(withSuccess(details, MediaType.APPLICATION_JSON));
        server.expect(requestTo(CREDS.baseUrl() + "/repos/owner/repo/pulls/7"))
                .andRespond(withSuccess("diff", MediaType.TEXT_PLAIN));
        server.expect(requestTo(CREDS.baseUrl() + "/repos/owner/repo/pulls/7"))
                .andRespond(withSuccess(details, MediaType.APPLICATION_JSON));
        server.expect(requestTo(CREDS.baseUrl() + "/repos/owner/repo/compare/base...head"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        ReviewSnapshot snapshot = client.getReviewSnapshot("owner", "repo", 7L);
        assertNull(snapshot.effectiveBaseSha());
        assertFalse(snapshot.pinned());
        assertEquals("diff", snapshot.diff());
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

        server.expect(requestTo("https://api.github.com/repos/owner/repo/compare/base...new-head"))
                .andRespond(withSuccess("{\"merge_base_commit\":{\"sha\":\"merge-base\"}}", MediaType.APPLICATION_JSON));

        ReviewSnapshot snapshot = client.getReviewSnapshot("owner", "repo", 7L);

        server.verify();
        assertEquals("new-head", snapshot.headSha());
        assertEquals("base", snapshot.baseSha());
        assertEquals("merge-base", snapshot.effectiveBaseSha());
        assertTrue(snapshot.pinned());
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

    @Test
    void replyToReviewThread_postsReplyAndReturnsRemoteId() {
        RestClient.Builder builder = RestClient.builder().baseUrl(CREDS.baseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubApiClient client = new GitHubApiClient(builder.build(), CREDS);
        server.expect(requestTo(CREDS.baseUrl() + "/repos/owner/repo/pulls/7/comments"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.in_reply_to").value(123))
                .andExpect(jsonPath("$.body").value("reply"))
                .andRespond(withSuccess("{\"id\":456}", MediaType.APPLICATION_JSON));
        assertEquals(java.util.Optional.of("456"), client.replyToReviewThread("owner", "repo", 7L, "123", "reply"));
        server.verify();
    }

    @Test
    void graphQlEndpoint_usesEnterpriseApiPath() {
        assertEquals("https://ghe.example/api/graphql",
                GitHubApiClient.graphQlEndpoint("https://ghe.example/api/v3"));
        assertEquals("https://api.github.com/graphql",
                GitHubApiClient.graphQlEndpoint("https://api.github.com"));
    }

    @Test
    void resolveReviewThread_forbiddenGraphQlDowngradesCapability() {
        var restBuilder = RestClient.builder().baseUrl(CREDS.baseUrl());
        var restServer = MockRestServiceServer.bindTo(restBuilder).build();
        var graphBuilder = RestClient.builder().baseUrl("https://api.github.com/graphql");
        var graphServer = MockRestServiceServer.bindTo(graphBuilder).build();
        GitHubApiClient client = new GitHubApiClient(restBuilder.build(), CREDS, graphBuilder.build());
        graphServer.expect(requestTo("https://api.github.com/graphql"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.query").value(org.hamcrest.Matchers.containsString("reviewThreads")))
                .andRespond(withSuccess("{\"data\":{\"repository\":{\"pullRequest\":{\"reviewThreads\":{\"nodes\":[{\"id\":\"PRRT_node\",\"comments\":{\"nodes\":[{\"databaseId\":123}]}}],\"pageInfo\":{\"hasNextPage\":false,\"endCursor\":null}}}}}}",
                        MediaType.APPLICATION_JSON));
        graphServer.expect(requestTo("https://api.github.com/graphql"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.query").value(org.hamcrest.Matchers.containsString("resolveReviewThread")))
                .andRespond(withSuccess("{\"errors\":[{\"message\":\"Resource not accessible by integration\"}]}",
                        MediaType.APPLICATION_JSON));
        assertDoesNotThrow(() -> client.resolveReviewThread("owner", "repo", 7L, "123"));
        assertFalse(client.threadCapabilities().canResolveThread());
        graphServer.verify();
        restServer.verify();
    }

    @Test
    void listReviewThreadComments_pagesThroughAllComments() {
        RestClient.Builder builder = RestClient.builder().baseUrl(CREDS.baseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubApiClient client = new GitHubApiClient(builder.build(), CREDS);
        String fullPage = "[" + String.join(",", java.util.Collections.nCopies(100,
                "{\"id\":1,\"body\":\"comment\",\"user\":{\"login\":\"alice\"}}")) + "]";
        server.expect(requestTo(CREDS.baseUrl() + "/repos/owner/repo/pulls/7/comments?per_page=100&page=1"))
                .andRespond(withSuccess(fullPage, MediaType.APPLICATION_JSON));
        server.expect(requestTo(CREDS.baseUrl() + "/repos/owner/repo/pulls/7/comments?per_page=100&page=2"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        var scan = client.listReviewThreadComments("owner", "repo", 7L);
        assertTrue(scan.complete());
        assertEquals(100, scan.comments().size());
        server.verify();
    }

    @Test
    void listReviewThreadComments_pageFailureIsIncomplete() {
        RestClient.Builder builder = RestClient.builder().baseUrl(CREDS.baseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubApiClient client = new GitHubApiClient(builder.build(), CREDS);
        String fullPage = "[" + String.join(",", java.util.Collections.nCopies(100,
                "{\"id\":1,\"body\":\"comment\"}")) + "]";
        server.expect(requestTo(CREDS.baseUrl() + "/repos/owner/repo/pulls/7/comments?per_page=100&page=1"))
                .andRespond(withSuccess(fullPage, MediaType.APPLICATION_JSON));
        server.expect(requestTo(CREDS.baseUrl() + "/repos/owner/repo/pulls/7/comments?per_page=100&page=2"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        var scan = client.listReviewThreadComments("owner", "repo", 7L);
        assertFalse(scan.complete());
        assertEquals(100, scan.comments().size());
        server.verify();
    }

    @Test
    void postPullRequestCommentReturningId_readsProviderId() {
        RestClient.Builder builder = RestClient.builder().baseUrl(CREDS.baseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubApiClient client = new GitHubApiClient(builder.build(), CREDS);
        server.expect(requestTo(CREDS.baseUrl() + "/repos/owner/repo/issues/7/comments"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"id\":987}", MediaType.APPLICATION_JSON));
        assertEquals(java.util.Optional.of("987"),
                client.postPullRequestCommentReturningId("owner", "repo", 7L, "summary"));
        server.verify();
    }


    @Test
    void resolveReviewThread_successLooksUpAndResolvesGraphQlNode() {
        var restBuilder = RestClient.builder().baseUrl(CREDS.baseUrl());
        MockRestServiceServer.bindTo(restBuilder).build();
        var graphBuilder = RestClient.builder().baseUrl("https://api.github.com/graphql");
        var graphServer = MockRestServiceServer.bindTo(graphBuilder).build();
        GitHubApiClient client = new GitHubApiClient(restBuilder.build(), CREDS, graphBuilder.build());
        graphServer.expect(requestTo("https://api.github.com/graphql"))
                .andRespond(withSuccess("{\"data\":{\"repository\":{\"pullRequest\":{\"reviewThreads\":{\"nodes\":[{\"id\":\"PRRT_thread\",\"comments\":{\"nodes\":[{\"databaseId\":123}]}}],\"pageInfo\":{\"hasNextPage\":false,\"endCursor\":null}}}}}}",
                        MediaType.APPLICATION_JSON));
        graphServer.expect(requestTo("https://api.github.com/graphql"))
                .andExpect(jsonPath("$.variables.threadId").value("PRRT_thread"))
                .andRespond(withSuccess("{\"data\":{\"resolveReviewThread\":{\"thread\":{\"isResolved\":true}}}}",
                        MediaType.APPLICATION_JSON));
        client.resolveReviewThread("owner", "repo", 7L, "123");
        assertTrue(client.threadCapabilities().canResolveThread());
        graphServer.verify();
    }

    @Test
    void listReviewThreadComments_mapsReplyIdentityAndTimestamp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(CREDS.baseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubApiClient client = new GitHubApiClient(builder.build(), CREDS);
        server.expect(requestTo(CREDS.baseUrl() + "/repos/owner/repo/pulls/7/comments?per_page=100&page=1"))
                .andRespond(withSuccess("[{\"id\":55,\"in_reply_to_id\":44,\"created_at\":\"2026-10-08T10:00:00Z\",\"user\":{\"login\":\"alice\"}}]",
                        MediaType.APPLICATION_JSON));
        var comment = client.listReviewThreadComments("owner", "repo", 7L).comments().getFirst();
        assertEquals("44", comment.getThreadId());
        assertEquals("44", comment.getParentId());
        assertEquals("2026-10-08T10:00:00Z", comment.getCreatedAt());
        assertEquals("alice", comment.getUserLogin());
        server.verify();
    }


    @Test
    void resolveReviewThread_enterpriseUsesApiGraphQlEndpoint() {
        var enterprise = RepositoryCredentials.of(
                "https://ghe.example/api/v3", "https://ghe.example", "token");
        RestClient.Builder builder = RestClient.builder().baseUrl(enterprise.baseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubApiClient client = new GitHubApiClient(builder.build(), enterprise);
        String endpoint = "https://ghe.example/api/graphql";
        server.expect(requestTo(endpoint))
                .andRespond(withSuccess("{\"data\":{\"repository\":{\"pullRequest\":{\"reviewThreads\":{\"nodes\":[{\"id\":\"PRRT_thread\",\"comments\":{\"nodes\":[{\"databaseId\":123}]}}],\"pageInfo\":{\"hasNextPage\":false,\"endCursor\":null}}}}}}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(endpoint))
                .andRespond(withSuccess("{\"data\":{\"resolveReviewThread\":{\"thread\":{\"isResolved\":true}}}}",
                        MediaType.APPLICATION_JSON));
        client.resolveReviewThread("owner", "repo", 7L, "123");
        server.verify();
    }


    @Test
    void postReviewCommentReturningId_returnsReviewId() {
        RestClient.Builder builder = RestClient.builder().baseUrl(CREDS.baseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubApiClient client = new GitHubApiClient(builder.build(), CREDS);
        server.expect(requestTo(CREDS.baseUrl() + "/repos/owner/repo/pulls/7/reviews"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"id\":789}", MediaType.APPLICATION_JSON));
        assertEquals(java.util.Optional.of("789"),
                client.postReviewCommentReturningId("owner", "repo", 7L, "review body"));
        server.verify();
    }

}
