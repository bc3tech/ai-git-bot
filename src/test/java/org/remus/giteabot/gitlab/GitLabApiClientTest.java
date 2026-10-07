package org.remus.giteabot.gitlab;

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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * Unit tests for {@link GitLabApiClient} verifying that it correctly implements
 * {@link RepositoryApiClient} and exposes the expected base URL, clone URL, and token.
 */
class GitLabApiClientTest {

    private static final RepositoryCredentials CREDS =
            RepositoryCredentials.of("https://gitlab.example.com", "https://gitlab.example.com", "glpat-token123");

    @Test
    void implementsRepositoryApiClient() {
        GitLabApiClient client = new GitLabApiClient(null, CREDS);
        assertInstanceOf(RepositoryApiClient.class, client);
    }

    @Test
    void getBaseUrl_returnsConfiguredUrl() {
        GitLabApiClient client = new GitLabApiClient(null, CREDS);
        assertEquals("https://gitlab.example.com", client.getBaseUrl());
    }

    @Test
    void getCloneUrl_returnsConfiguredUrl() {
        GitLabApiClient client = new GitLabApiClient(null, CREDS);
        assertEquals("https://gitlab.example.com", client.getCloneUrl());
    }

    @Test
    void getToken_returnsConfiguredToken() {
        GitLabApiClient client = new GitLabApiClient(null, CREDS);
        assertEquals("glpat-token123", client.getToken());
    }

    @Test
    void constructorWithSelfHostedUrl() {
        var selfHostedCreds = RepositoryCredentials.of(
                "https://git.mycompany.com", "https://git.mycompany.com", "glpat-abc");
        GitLabApiClient client = new GitLabApiClient(null, selfHostedCreds);
        assertEquals("https://git.mycompany.com", client.getBaseUrl());
        assertEquals("https://git.mycompany.com", client.getCloneUrl());
    }

    @Test
    void encodeProjectPath_buildsProjectPath() {
        assertEquals("owner/repo", GitLabApiClient.encodeProjectPath("owner", "repo"));
        assertEquals("my-org/my-project", GitLabApiClient.encodeProjectPath("my-org", "my-project"));
    }

    @Test
    void addPullRequestReaction_postsEyesToMergeRequestAwardEndpoint() {
        RestClient.Builder builder = RestClient.builder().baseUrl(CREDS.baseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitLabApiClient client = new GitLabApiClient(builder.build(), CREDS);

        server.expect(requestTo(
                        "https://gitlab.example.com/api/v4/projects/owner%2Frepo/merge_requests/42/award_emoji"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.name").value("eyes"))
                .andRespond(withSuccess());

        client.addPullRequestReaction("owner", "repo", 42L, "eyes");

        server.verify();
    }

    @Test
    void addIssueReaction_postsEyesToIssueAwardEndpoint() {
        RestClient.Builder builder = RestClient.builder().baseUrl(CREDS.baseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitLabApiClient client = new GitLabApiClient(builder.build(), CREDS);

        server.expect(requestTo(
                        "https://gitlab.example.com/api/v4/projects/owner%2Frepo/issues/12/award_emoji"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.name").value("eyes"))
                .andRespond(withSuccess());

        client.addIssueReaction("owner", "repo", 12L, "eyes");

        server.verify();
    }

    @Test
    void postReviewActionRequestChanges_callsGitLabRequestChangesEndpoint() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitlab.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitLabApiClient client = new GitLabApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://gitlab.example.com/api/v4/projects/owner%2Frepo/merge_requests/7/request_changes"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess());

        client.postReviewAction("owner", "repo", 7L, PostReviewAction.REQUEST_CHANGES);

        server.verify();
    }

    @Test
    void searchIssues_returnsIssueList() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitlab.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitLabApiClient client = new GitLabApiClient(builder.build(), CREDS);

        server.expect(requestTo(
                        "https://gitlab.example.com/api/v4/projects/owner%2Frepo/issues?search=authentication%20bug&scope=all"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "[{\"iid\":1,\"title\":\"Auth bug\",\"description\":\"Login fails\",\"state\":\"opened\"}]",
                        MediaType.APPLICATION_JSON));

        List<Map<String, Object>> issues = client.searchIssues("owner", "repo", "authentication bug");

        server.verify();
        assertEquals(1, issues.size());
        assertEquals("Auth bug", issues.getFirst().get("title"));
    }

    @Test
    void searchIssues_returnsEmptyListForEmptyResults() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitlab.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitLabApiClient client = new GitLabApiClient(builder.build(), CREDS);

        server.expect(requestTo(
                        "https://gitlab.example.com/api/v4/projects/owner%2Frepo/issues?search=&scope=all"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        List<Map<String, Object>> issues = client.searchIssues("owner", "repo", "");

        server.verify();
        assertTrue(issues.isEmpty());
    }

    @Test
    void getIssueComments_fetchesIssueNotesWithPageLimit() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitlab.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitLabApiClient client = new GitLabApiClient(builder.build(), CREDS);

        server.expect(requestTo(
                        "https://gitlab.example.com/api/v4/projects/owner%2Frepo/issues/42/notes?per_page=50"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[{\"id\":201,\"body\":\"First note\"}]", MediaType.APPLICATION_JSON));

        List<Map<String, Object>> comments = client.getIssueComments("owner", "repo", 42L);

        server.verify();
        assertEquals(1, comments.size());
        assertEquals(201, ((Number) comments.getFirst().get("id")).intValue());
        assertEquals("First note", comments.getFirst().get("body"));
    }

    @Test
    void createIssue_returnsIssueIid() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitlab.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitLabApiClient client = new GitLabApiClient(builder.build(), CREDS);

        server.expect(requestTo(
                        "https://gitlab.example.com/api/v4/projects/owner%2Frepo/issues"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"iid\":42,\"title\":\"My Issue\",\"description\":\"Issue body\"}",
                        MediaType.APPLICATION_JSON));

        Long issueNumber = client.createIssue("owner", "repo", "My Issue", "Issue body");

        server.verify();
        assertEquals(42L, issueNumber);
    }

    @Test
    void assignIssue_resolvesUserThenUpdatesIssue() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitlab.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitLabApiClient client = new GitLabApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://gitlab.example.com/api/v4/users?username=alice"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[{\"id\":7,\"username\":\"alice\"}]", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://gitlab.example.com/api/v4/projects/owner%2Frepo/issues/42"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(jsonPath("$.assignee_ids[0]").value(7))
                .andRespond(withSuccess("{\"iid\":42}", MediaType.APPLICATION_JSON));

        client.assignIssue("owner", "repo", 42L, "alice");

        server.verify();
    }

    @Test
    void assignIssue_unknownUserThrowsWithoutUpdatingIssue() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitlab.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitLabApiClient client = new GitLabApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://gitlab.example.com/api/v4/users?username=ghost"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThrows(IllegalArgumentException.class,
                () -> client.assignIssue("owner", "repo", 42L, "ghost"));

        server.verify();
    }

    @Test
    void getReviewSnapshot_usesLatestVersionDiffsAndCommitRefs() {
        RestClient.Builder builder = RestClient.builder().baseUrl(CREDS.baseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitLabApiClient client = new GitLabApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://gitlab.example.com/api/v4/projects/owner%2Frepo/merge_requests/7/versions"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[{\"id\":12,\"base_commit_sha\":\"base\","
                        + "\"start_commit_sha\":\"start\",\"head_commit_sha\":\"head\"}]",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://gitlab.example.com/api/v4/projects/owner%2Frepo/merge_requests/7/versions/12"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"diffs\":["
                        + "{\"old_path\":\"new.txt\",\"new_path\":\"new.txt\","
                        + "\"diff\":\"@@ -0,0 +1 @@\\n+hello\\n\",\"new_file\":true},"
                        + "{\"old_path\":\"gone.txt\",\"new_path\":\"gone.txt\","
                        + "\"diff\":\"@@ -1 +0,0 @@\\n-old\\n\",\"deleted_file\":true}]}"
                        , MediaType.APPLICATION_JSON));

        ReviewSnapshot snapshot = client.getReviewSnapshot("owner", "repo", 7L);

        server.verify();
        assertEquals("head", snapshot.headSha());
        assertEquals("base", snapshot.baseSha());
        assertEquals("start", snapshot.startSha());
        assertTrue(snapshot.inlineSupported());
        assertTrue(snapshot.diff().contains("--- /dev/null\n+++ b/new.txt\n"));
        assertTrue(snapshot.diff().contains("--- a/gone.txt\n+++ /dev/null\n"));
    }

    @Test
    void publishInlineReview_postsDiscussionWithContextPositionThenSummaryAndAction() {
        RestClient.Builder builder = RestClient.builder().baseUrl(CREDS.baseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitLabApiClient client = new GitLabApiClient(builder.build(), CREDS);
        ReviewSnapshot snapshot = new ReviewSnapshot("head", "base", "start", "diff", true);
        ReviewAnchorComment comment = new ReviewAnchorComment("finding-1", "new/A.java", "old/A.java",
                DiffSide.NEW, 8, 3, 8, "Review this context");

        server.expect(requestTo("https://gitlab.example.com/api/v4/projects/owner%2Frepo/merge_requests/7/discussions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.body").value("Review this context"))
                .andExpect(jsonPath("$.position.position_type").value("text"))
                .andExpect(jsonPath("$.position.base_sha").value("base"))
                .andExpect(jsonPath("$.position.start_sha").value("start"))
                .andExpect(jsonPath("$.position.head_sha").value("head"))
                .andExpect(jsonPath("$.position.old_path").value("old/A.java"))
                .andExpect(jsonPath("$.position.new_path").value("new/A.java"))
                .andExpect(jsonPath("$.position.old_line").value(3))
                .andExpect(jsonPath("$.position.new_line").value(8))
                .andRespond(withSuccess("{\"id\":\"discussion-1\",\"notes\":[{\"position\":{}}]}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://gitlab.example.com/api/v4/projects/owner%2Frepo/merge_requests/7/notes"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.body").value("Summary"))
                .andRespond(withSuccess());
        server.expect(requestTo("https://gitlab.example.com/api/v4/projects/owner%2Frepo/merge_requests/7/request_changes"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess());

        ReviewPublicationResult result = client.publishInlineReview("owner", "repo", 7L, snapshot,
                "Summary", List.of(comment), PostReviewAction.REQUEST_CHANGES);

        server.verify();
        assertEquals(ReviewPublicationResult.Status.PUBLISHED, result.status());
        assertEquals(ReviewPublicationResult.Delivery.CONFIRMED, result.review());
        assertEquals("discussion-1", result.comments().getFirst().remoteId());
    }

    @Test
    void publishInlineReview_returnsRejectedWhenAllWritesAreRejected() {
        RestClient.Builder builder = RestClient.builder().baseUrl(CREDS.baseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitLabApiClient client = new GitLabApiClient(builder.build(), CREDS);
        ReviewAnchorComment comment = new ReviewAnchorComment("finding-1", "src/A.java", null,
                DiffSide.NEW, 2, null, 2, "Comment");
        server.expect(requestTo("https://gitlab.example.com/api/v4/projects/owner%2Frepo/merge_requests/7/discussions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY));
        server.expect(requestTo("https://gitlab.example.com/api/v4/projects/owner%2Frepo/merge_requests/7/notes"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        ReviewPublicationResult result = client.publishInlineReview("owner", "repo", 7L,
                new ReviewSnapshot("head", "base", "start", "diff", true), "Summary",
                List.of(comment), PostReviewAction.NONE);

        server.verify();
        assertEquals(ReviewPublicationResult.Status.REJECTED, result.status());
        assertTrue(result.nothingWritten());
    }

    @Test
    void publishInlineReview_marksCommentMisplacedWhenResponseOmitsPosition() {
        RestClient.Builder builder = RestClient.builder().baseUrl(CREDS.baseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitLabApiClient client = new GitLabApiClient(builder.build(), CREDS);
        ReviewAnchorComment comment = new ReviewAnchorComment("finding-1", "src/A.java", null,
                DiffSide.NEW, 2, null, 2, "Comment");
        server.expect(requestTo("https://gitlab.example.com/api/v4/projects/owner%2Frepo/merge_requests/7/discussions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"id\":\"discussion-2\",\"notes\":[{\"id\":99}]}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://gitlab.example.com/api/v4/projects/owner%2Frepo/merge_requests/7/notes"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess());

        ReviewPublicationResult result = client.publishInlineReview("owner", "repo", 7L,
                new ReviewSnapshot("head", "base", "start", "diff", true), "Summary",
                List.of(comment), PostReviewAction.NONE);

        server.verify();
        assertEquals(ReviewPublicationResult.Status.PARTIAL, result.status());
        assertEquals(ReviewPublicationResult.Delivery.MISPLACED, result.comments().getFirst().delivery());
    }

    @Test
    void publishInlineReview_returnsUnknownWhenOnlyWritesHaveUncertainOutcomes() {
        RestClient.Builder builder = RestClient.builder().baseUrl(CREDS.baseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitLabApiClient client = new GitLabApiClient(builder.build(), CREDS);
        ReviewAnchorComment comment = new ReviewAnchorComment("finding-1", "src/A.java", null,
                DiffSide.NEW, 2, null, 2, "Comment");
        server.expect(requestTo("https://gitlab.example.com/api/v4/projects/owner%2Frepo/merge_requests/7/discussions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        server.expect(requestTo("https://gitlab.example.com/api/v4/projects/owner%2Frepo/merge_requests/7/notes"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        ReviewPublicationResult result = client.publishInlineReview("owner", "repo", 7L,
                new ReviewSnapshot("head", "base", "start", "diff", true), "Summary",
                List.of(comment), PostReviewAction.NONE);

        server.verify();
        assertEquals(ReviewPublicationResult.Status.UNKNOWN, result.status());
        assertFalse(result.nothingWritten());
    }
}
