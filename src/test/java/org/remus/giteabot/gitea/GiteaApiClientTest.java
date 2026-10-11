package org.remus.giteabot.gitea;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.remus.giteabot.repository.PostReviewAction;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.model.PullRequestHead;
import org.remus.giteabot.repository.model.RepositoryCredentials;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
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
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;

/**
 * Unit tests for {@link GiteaApiClient}.
 */
class GiteaApiClientTest {

    private static final RepositoryCredentials CREDS =
            RepositoryCredentials.of("https://gitea.example.com", "https://gitea.example.com", "gitea-token");

    @Test
    void removePullRequestReaction_usesIssueReactionDeleteEndpoint() {
        RestClient.Builder builder = RestClient.builder().baseUrl(CREDS.baseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GiteaApiClient client = new GiteaApiClient(builder.build(), CREDS);
        server.expect(requestTo(CREDS.baseUrl() + "/api/v1/repos/owner/repo/issues/7/reactions"))
                .andExpect(method(HttpMethod.DELETE)).andExpect(jsonPath("$.content").value("eyes"))
                .andRespond(withSuccess());
        client.removePullRequestReaction("owner", "repo", 7L, "eyes");
        server.verify();
    }

    @Test
    void implementsRepositoryApiClient() {
        GiteaApiClient client = new GiteaApiClient(null, CREDS);
        assertInstanceOf(RepositoryApiClient.class, client);
    }

    @Test
    void getRepositoryRemote_buildsCompleteHttpUrl() {
        GiteaApiClient client = new GiteaApiClient(null, CREDS);

        assertEquals("https://gitea.example.com/owner/repo.git",
                client.getRepositoryRemote("owner", "repo"));
    }

    @Test
    void managedSsh_detectsEndpointAndRegistersWritableUserKey() {
        RestClient.Builder builder = RestClient.builder().baseUrl(CREDS.baseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GiteaApiClient client = new GiteaApiClient(builder.build(), CREDS);
        server.expect(requestTo(CREDS.baseUrl() + "/api/v1/repos/search?limit=1&private=true"))
                .andRespond(withSuccess("{\"data\":[{\"ssh_url\":\"ssh://git@gitea.example.com:2222/owner/repo.git\"}]}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(CREDS.baseUrl() + "/api/v1/user"))
                .andRespond(withSuccess("{\"id\":17}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(CREDS.baseUrl() + "/api/v1/user/keys"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.title").value("unique-title"))
                .andExpect(jsonPath("$.key").value("ssh-ed25519 public"))
                .andExpect(jsonPath("$.read_only").value(false))
                .andRespond(withSuccess("{\"id\":42}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(CREDS.baseUrl() + "/api/v1/user/keys/42"))
                .andExpect(method(HttpMethod.DELETE)).andRespond(withSuccess());
        assertEquals("ssh://git@gitea.example.com:2222/owner/repo.git", client.getAnySshCloneUrl());
        assertEquals(17L, client.getCurrentUserId());
        assertEquals(42L, client.createSshKey("unique-title", "ssh-ed25519 public"));
        client.deleteSshKey(42L);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"data\":[]}", "{\"data\":[{\"ssh_url\":\"git@-evil:repo.git\"}]}",
            "{\"data\":[{\"ssh_url\":\"https://host/repo.git\"}]}"})
    void managedSsh_rejectsMissingOrUnsafeEndpoint(String response) {
        RestClient.Builder builder = RestClient.builder().baseUrl(CREDS.baseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GiteaApiClient client = new GiteaApiClient(builder.build(), CREDS);
        server.expect(requestTo(CREDS.baseUrl() + "/api/v1/repos/search?limit=1&private=true"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        assertThrows(IllegalStateException.class, client::getAnySshCloneUrl);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"id\":0}", "{\"id\":-1}", "{\"id\":\"42\"}"})
    void managedSsh_rejectsMissingOrInvalidRegistrationId(String response) {
        RestClient.Builder builder = RestClient.builder().baseUrl(CREDS.baseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GiteaApiClient client = new GiteaApiClient(builder.build(), CREDS);
        server.expect(requestTo(CREDS.baseUrl() + "/api/v1/user/keys"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        assertThrows(IllegalStateException.class, () -> client.createSshKey("title", "public"));
        server.verify();
    }

    @Test
    void managedSsh_titleRecoveryReadsAllPagesEvenWhenServerLimitsPageSize() {
        RestClient.Builder builder = RestClient.builder().baseUrl(CREDS.baseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GiteaApiClient client = new GiteaApiClient(builder.build(), CREDS);
        server.expect(requestTo(CREDS.baseUrl() + "/api/v1/user/keys?page=1&limit=50"))
                .andRespond(withSuccess("[{\"id\":1,\"title\":\"unrelated\"}]", MediaType.APPLICATION_JSON));
        server.expect(requestTo(CREDS.baseUrl() + "/api/v1/user/keys?page=2&limit=50"))
                .andRespond(withSuccess("[{\"id\":42,\"title\":\"unique-title\"}]", MediaType.APPLICATION_JSON));
        server.expect(requestTo(CREDS.baseUrl() + "/api/v1/user/keys?page=3&limit=50"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        assertEquals(List.of(42L), client.getSshKeyIdsByTitle("unique-title"));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "[{\"title\":\"key\"}]", "[{\"id\":42}]"})
    void managedSsh_ambiguousKeyListIsNotTreatedAsAbsent(String response) {
        RestClient.Builder builder = RestClient.builder().baseUrl(CREDS.baseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GiteaApiClient client = new GiteaApiClient(builder.build(), CREDS);
        server.expect(requestTo(CREDS.baseUrl() + "/api/v1/user/keys?page=1&limit=50"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        assertThrows(IllegalStateException.class, client::getSshKeyIds);
        server.verify();
    }

    @Test
    void getPullRequestHead_resolvesForkRepositoryAndBranch() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitea.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GiteaApiClient client = new GiteaApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://gitea.example.com/api/v1/repos/base/project/pulls/7"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"head":{"ref":"main","sha":"abc123","repo":{
                          "name":"project","full_name":"contributor/project",
                          "owner":{"login":"contributor"}}}}
                        """, MediaType.APPLICATION_JSON));

        PullRequestHead head = client.getPullRequestHead("base", "project", 7L, "main");

        assertEquals("contributor", head.owner());
        assertEquals("project", head.repository());
        assertEquals("main", head.branch());
        assertEquals("abc123", head.sha());
        server.verify();
    }

    @Test
    void getPullRequestHead_rejectsBranchMismatch() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitea.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GiteaApiClient client = new GiteaApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://gitea.example.com/api/v1/repos/base/project/pulls/7"))
                .andRespond(withSuccess("""
                        {"head":{"ref":"feature/source","sha":"abc123","repo":{
                          "name":"project","owner":{"login":"contributor"}}}}
                        """, MediaType.APPLICATION_JSON));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> client.getPullRequestHead("base", "project", 7L, "main"));

        assertTrue(error.getMessage().contains("does not match"));
        server.verify();
    }

    @Test
    void getPullRequestHead_rejectsMissingSourceRepository() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitea.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GiteaApiClient client = new GiteaApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://gitea.example.com/api/v1/repos/base/project/pulls/7"))
                .andRespond(withSuccess("{" + "\"head\":{\"ref\":\"main\",\"sha\":\"abc123\"}}",
                        MediaType.APPLICATION_JSON));

        assertThrows(IllegalStateException.class,
                () -> client.getPullRequestHead("base", "project", 7L, "main"));
        server.verify();
    }

    @Test
    void getRepositoryRemote_rejectsCredentialBearingHttpBase() {
        GiteaApiClient client = new GiteaApiClient(null,
                RepositoryCredentials.of("https://gitea.example.com",
                        "https://user:secret@gitea.example.com", "gitea-token"));

        assertThrows(IllegalStateException.class,
                () -> client.getRepositoryRemote("owner", "repo"));
    }

    @Test
    void getRepositoryRemote_acceptsCaseInsensitiveHttpScheme() {
        GiteaApiClient client = new GiteaApiClient(null,
                RepositoryCredentials.of("https://gitea.example.com",
                        "HTTPS://gitea.example.com", "gitea-token"));

        assertEquals("https://gitea.example.com/owner/repo.git",
                client.getRepositoryRemote("owner", "repo"));
    }

    @Test
    void getRepositoryRemote_rejectsUnsupportedScheme() {
        GiteaApiClient client = new GiteaApiClient(null,
                RepositoryCredentials.of("https://gitea.example.com",
                        "ftp://gitea.example.com", "gitea-token"));

        assertThrows(IllegalStateException.class,
                () -> client.getRepositoryRemote("owner", "repo"));
    }

    @Test
    void getRepositoryRemote_sshEnabled_usesRepositorySshUrl() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitea.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GiteaApiClient client = new GiteaApiClient(builder.build(),
                CREDS.withSsh("private-key", "gitea.example.com ssh-ed25519 host-key"));

        server.expect(requestTo("https://gitea.example.com/api/v1/repos/owner/repo"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"ssh_url\":\"git@gitea.example.com:owner/repo.git\"}",
                        MediaType.APPLICATION_JSON));

        assertEquals("git@gitea.example.com:owner/repo.git",
                client.getRepositoryRemote("owner", "repo"));
        server.verify();
    }

    @Test
    void getRepositoryRemote_acceptsMatchingSshUrlWithCustomPort() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitea.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GiteaApiClient client = new GiteaApiClient(builder.build(),
                CREDS.withSsh("private-key", "[gitea.example.com]:2222 ssh-ed25519 host-key"));

        server.expect(requestTo("https://gitea.example.com/api/v1/repos/owner/repo"))
                .andRespond(withSuccess(
                        "{\"ssh_url\":\"ssh://git@gitea.example.com:2222/owner/repo.git\"}",
                        MediaType.APPLICATION_JSON));

        assertEquals("ssh://git@gitea.example.com:2222/owner/repo.git",
                client.getRepositoryRemote("owner", "repo"));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "https://gitea.example.com/owner/repo.git", "file:///tmp/repo.git",
            "/tmp/repo.git", "C:/repos/repo.git", "C:../repo.git", "-uploader", "not-a-remote",
            "git@gitea.example.com", "ssh:///owner/repo.git",
            "SSH://git@gitea.example.com/owner/repo.git",
            "ssh://%20-option@gitea.example.com/owner/repo.git",
            "git@[gitea.example.com:owner/repo.git", "git@host]:owner/repo.git",
            "git@[host]suffix:owner/repo.git"
    })
    void getRepositoryRemote_rejectsNonSshApiValues(String sshUrl) {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitea.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GiteaApiClient client = new GiteaApiClient(builder.build(),
                CREDS.withSsh("private-key", "gitea.example.com ssh-ed25519 host-key"));

        server.expect(requestTo("https://gitea.example.com/api/v1/repos/owner/repo"))
                .andRespond(withSuccess("{\"ssh_url\":\"" + sshUrl + "\"}", MediaType.APPLICATION_JSON));

        assertThrows(IllegalStateException.class,
                () -> client.getRepositoryRemote("owner", "repo"));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "git@other.example.com:owner/repo.git",
            "ssh://git@gitea.example.com:2222/owner/repo.git"
    })
    void getRepositoryRemote_rejectsCanonicalKnownHostsEndpointMismatch(String sshUrl) {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitea.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GiteaApiClient client = new GiteaApiClient(builder.build(),
                CREDS.withSsh("private-key", "gitea.example.com ssh-ed25519 host-key"));

        server.expect(requestTo("https://gitea.example.com/api/v1/repos/owner/repo"))
                .andRespond(withSuccess("{\"ssh_url\":\"" + sshUrl + "\"}", MediaType.APPLICATION_JSON));

        assertThrows(IllegalStateException.class,
                () -> client.getRepositoryRemote("owner", "repo"));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "|1|hash|salt ssh-ed25519 host-key",
            "*.example.com ssh-ed25519 host-key"
    })
    void getRepositoryRemote_preservesManualKnownHostsCompatibility(String knownHosts) {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitea.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GiteaApiClient client = new GiteaApiClient(builder.build(),
                CREDS.withSsh("private-key", knownHosts));

        server.expect(requestTo("https://gitea.example.com/api/v1/repos/owner/repo"))
                .andRespond(withSuccess(
                        "{\"ssh_url\":\"git@other.example.com:owner/repo.git\"}",
                        MediaType.APPLICATION_JSON));

        assertEquals("git@other.example.com:owner/repo.git",
                client.getRepositoryRemote("owner", "repo"));
        server.verify();
    }

    @Test
    void getIssueComments_fetchesAllIssueCommentsOnce() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitea.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GiteaApiClient client = new GiteaApiClient(builder.build(), CREDS);

        server.expect(requestTo(
                        "https://gitea.example.com/api/v1/repos/owner/repo/issues/42/comments"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[{\"id\":401,\"body\":\"First comment\"}]", MediaType.APPLICATION_JSON));

        List<Map<String, Object>> comments = client.getIssueComments("owner", "repo", 42L);

        server.verify();
        assertEquals(1, comments.size());
        assertEquals(401, ((Number) comments.getFirst().get("id")).intValue());
        assertEquals("First comment", comments.getFirst().get("body"));
    }

    @Test
    void postReview_approve_submitsSingleReviewWithBodyAndEvent() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitea.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GiteaApiClient client = new GiteaApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://gitea.example.com/api/v1/repos/owner/repo/pulls/7/reviews"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.body").value("The findings"))
                .andExpect(jsonPath("$.event").value("APPROVED"))
                .andRespond(withSuccess());

        client.postReview("owner", "repo", 7L, "The findings", PostReviewAction.APPROVE);

        server.verify();
    }

    @Test
    void postReview_none_submitsSingleCommentReview() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitea.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GiteaApiClient client = new GiteaApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://gitea.example.com/api/v1/repos/owner/repo/pulls/7/reviews"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.body").value("Just a comment"))
                .andExpect(jsonPath("$.event").value("COMMENT"))
                .andRespond(withSuccess());

        client.postReview("owner", "repo", 7L, "Just a comment", PostReviewAction.NONE);

        server.verify();
    }

    @Test
    void assignIssue_patchesAssigneesAndVerifiesAssignment() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitea.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GiteaApiClient client = new GiteaApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://gitea.example.com/api/v1/repos/owner/repo/issues/42"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(jsonPath("$.assignees[0]").value("alice"))
                .andRespond(withSuccess());
        server.expect(requestTo("https://gitea.example.com/api/v1/repos/owner/repo/issues/42"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"number\":42,\"assignees\":[{\"login\":\"alice\"}]}",
                        MediaType.APPLICATION_JSON));

        client.assignIssue("owner", "repo", 42L, "alice");

        server.verify();
    }

    @Test
    void assignIssue_throwsWhenGiteaSilentlyDropsAssignee() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitea.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GiteaApiClient client = new GiteaApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://gitea.example.com/api/v1/repos/owner/repo/issues/42"))
                .andExpect(method(HttpMethod.PATCH))
                .andRespond(withSuccess());
        server.expect(requestTo("https://gitea.example.com/api/v1/repos/owner/repo/issues/42"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"number\":42,\"assignees\":[]}", MediaType.APPLICATION_JSON));

        assertThrows(IllegalArgumentException.class,
                () -> client.assignIssue("owner", "repo", 42L, "ghost"));

        server.verify();
    }

    @Test
    void addPullRequestReaction_postsEyesReactionToThePullRequest() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitea.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GiteaApiClient client = new GiteaApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://gitea.example.com/api/v1/repos/owner/repo/issues/42/reactions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.content").value("eyes"))
                .andRespond(withSuccess());

        client.addPullRequestReaction("owner", "repo", 42L, "eyes");

        server.verify();
    }

    @Test
    void removeReaction_deletesEyesReactionFromComment() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitea.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GiteaApiClient client = new GiteaApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://gitea.example.com/api/v1/repos/owner/repo/issues/comments/42/reactions"))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(jsonPath("$.content").value("eyes"))
                .andRespond(withSuccess());

        client.removeReaction("owner", "repo", 42L, "eyes");

        server.verify();
    }

    @Test
    void addIssueReaction_postsEyesReactionToTheIssue() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitea.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GiteaApiClient client = new GiteaApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://gitea.example.com/api/v1/repos/owner/repo/issues/12/reactions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.content").value("eyes"))
                .andRespond(withSuccess());

        client.addIssueReaction("owner", "repo", 12L, "eyes");

        server.verify();
    }

    @Test
    void giteaReviewThreadWritesAreConservativelyUnsupported() {
        GiteaApiClient client = new GiteaApiClient(null, CREDS);
        assertFalse(client.threadCapabilities().canReplyInThread());
        assertFalse(client.threadCapabilities().canEditComment());
        assertFalse(client.threadCapabilities().canResolveThread());
        assertThrows(UnsupportedOperationException.class,
                () -> client.replyToReviewThread("owner", "repo", 7L, "52", "reply"));
    }

    @Test
    void listReviewThreadComments_readsReviewCommentIdentity() {
        RestClient.Builder builder = RestClient.builder().baseUrl(CREDS.baseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GiteaApiClient client = new GiteaApiClient(builder.build(), CREDS);
        server.expect(requestTo(CREDS.baseUrl() + "/api/v1/repos/owner/repo/pulls/7/reviews?limit=50&page=1"))
                .andRespond(withSuccess("[{\"id\":52}]", MediaType.APPLICATION_JSON));
        server.expect(requestTo(CREDS.baseUrl() + "/api/v1/repos/owner/repo/pulls/7/reviews/52/comments"))
                .andRespond(withSuccess("[{\"id\":3491,\"body\":\"note\",\"pull_request_review_id\":52,\"created_at\":\"2026-10-08T10:00:00Z\",\"user\":{\"login\":\"alice\"}}]",
                        MediaType.APPLICATION_JSON));
        var scan = client.listReviewThreadComments("owner", "repo", 7L);
        assertTrue(scan.complete());
        assertEquals("52", scan.comments().getFirst().getThreadId());
        assertEquals("alice", scan.comments().getFirst().getUserLogin());
        server.verify();
    }


    @ParameterizedTest
    @ValueSource(ints = {50, 75})
    void listPullRequestComments_readsAllIssueCommentsOnceAndReviewSurfaces(int count) {
        RestClient.Builder builder = RestClient.builder().baseUrl(CREDS.baseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GiteaApiClient client = new GiteaApiClient(builder.build(), CREDS);
        String fullPage = "[" + String.join(",", java.util.Collections.nCopies(count, "{\"id\":1,\"body\":\"comment\"}")) + "]";
        server.expect(requestTo(CREDS.baseUrl() + "/api/v1/repos/owner/repo/issues/7/comments"))
                .andRespond(withSuccess(fullPage, MediaType.APPLICATION_JSON));
        String reviewsUrl = CREDS.baseUrl() + "/api/v1/repos/owner/repo/pulls/7/reviews?limit=50&page=1";
        server.expect(requestTo(reviewsUrl)).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server.expect(requestTo(reviewsUrl)).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        var scan = client.listPullRequestComments("owner", "repo", 7L);
        assertTrue(scan.complete());
        assertEquals(count, scan.comments().size());
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {50, 75})
    void getIssueComments_handlesLargeUnpaginatedLists(int count) {
        RestClient.Builder builder = RestClient.builder().baseUrl(CREDS.baseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GiteaApiClient client = new GiteaApiClient(builder.build(), CREDS);
        String body = "[" + String.join(",", java.util.Collections.nCopies(count, "{\"id\":1}")) + "]";
        server.expect(requestTo(CREDS.baseUrl() + "/api/v1/repos/owner/repo/issues/7/comments"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertEquals(count, client.getIssueComments("owner", "repo", 7L).size());
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {50, 75})
    void listReviewThreadComments_handlesLargeUnpaginatedLists(int count) {
        RestClient.Builder builder = RestClient.builder().baseUrl(CREDS.baseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GiteaApiClient client = new GiteaApiClient(builder.build(), CREDS);
        server.expect(requestTo(CREDS.baseUrl() + "/api/v1/repos/owner/repo/pulls/7/reviews?limit=50&page=1"))
                .andRespond(withSuccess("[{\"id\":52}]", MediaType.APPLICATION_JSON));
        String body = "[" + String.join(",", java.util.Collections.nCopies(count,
                "{\"id\":3491,\"pull_request_review_id\":52}")) + "]";
        server.expect(requestTo(CREDS.baseUrl() + "/api/v1/repos/owner/repo/pulls/7/reviews/52/comments"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        var scan = client.listReviewThreadComments("owner", "repo", 7L);
        assertTrue(scan.complete());
        assertEquals(count, scan.comments().size());
        server.verify();
    }

    @Test
    void listReviewThreadComments_readsMultipleReviewPages() {
        RestClient.Builder builder = RestClient.builder().baseUrl(CREDS.baseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GiteaApiClient client = new GiteaApiClient(builder.build(), CREDS);
        String body = "[" + java.util.stream.IntStream.rangeClosed(1, 50)
                .mapToObj(id -> "{\"id\":" + id + "}").collect(java.util.stream.Collectors.joining(",")) + "]";
        server.expect(requestTo(CREDS.baseUrl() + "/api/v1/repos/owner/repo/pulls/7/reviews?limit=50&page=1"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        server.expect(requestTo(CREDS.baseUrl() + "/api/v1/repos/owner/repo/pulls/7/reviews?limit=50&page=2"))
                .andRespond(withSuccess("[{\"id\":51}]", MediaType.APPLICATION_JSON));
        for (int id = 1; id <= 51; id++) {
            server.expect(requestTo(CREDS.baseUrl() + "/api/v1/repos/owner/repo/pulls/7/reviews/" + id + "/comments"))
                    .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        }
        assertTrue(client.listReviewThreadComments("owner", "repo", 7L).complete());
        server.verify();
    }

    @Test
    void listReviewThreadComments_stopsOnRepeatedReviewPagesAndMarksIncomplete() {
        RestClient.Builder builder = RestClient.builder().baseUrl(CREDS.baseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GiteaApiClient client = new GiteaApiClient(builder.build(), CREDS);
        String body = "[" + java.util.stream.IntStream.rangeClosed(1, 50)
                .mapToObj(id -> "{\"id\":" + id + "}").collect(java.util.stream.Collectors.joining(",")) + "]";
        for (int page = 1; page <= 2; page++) {
            server.expect(requestTo(CREDS.baseUrl() + "/api/v1/repos/owner/repo/pulls/7/reviews?limit=50&page=" + page))
                    .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        }
        for (int id = 1; id <= 50; id++) {
            server.expect(requestTo(CREDS.baseUrl() + "/api/v1/repos/owner/repo/pulls/7/reviews/" + id + "/comments"))
                    .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        }
        assertFalse(client.listReviewThreadComments("owner", "repo", 7L).complete());
        server.verify();
    }

    @Test
    void listPullRequestComments_marksFailedIssueScanIncomplete() {
        RestClient.Builder builder = RestClient.builder().baseUrl(CREDS.baseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GiteaApiClient client = new GiteaApiClient(builder.build(), CREDS);
        server.expect(requestTo(CREDS.baseUrl() + "/api/v1/repos/owner/repo/issues/7/comments"))
                .andRespond(withServerError());
        for (int i = 0; i < 2; i++) {
            server.expect(requestTo(CREDS.baseUrl() + "/api/v1/repos/owner/repo/pulls/7/reviews?limit=50&page=1"))
                    .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        }
        assertFalse(client.listPullRequestComments("owner", "repo", 7L).complete());
        server.verify();
    }

    @Test
    void listReviewThreadComments_boundsReviewPaginationAndMarksIncomplete() {
        RestClient.Builder builder = RestClient.builder().baseUrl(CREDS.baseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GiteaApiClient client = new GiteaApiClient(builder.build(), CREDS);
        for (int page = 1; page <= 100; page++) {
            int firstId = (page - 1) * 50 + 1;
            String body = "[" + java.util.stream.IntStream.range(firstId, firstId + 50)
                    .mapToObj(id -> "{\"id\":" + id + "}")
                    .collect(java.util.stream.Collectors.joining(",")) + "]";
            server.expect(requestTo(CREDS.baseUrl() + "/api/v1/repos/owner/repo/pulls/7/reviews?limit=50&page=" + page))
                    .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        }
        for (int id = 1; id <= 5000; id++) {
            server.expect(requestTo(CREDS.baseUrl() + "/api/v1/repos/owner/repo/pulls/7/reviews/" + id + "/comments"))
                    .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        }
        assertFalse(client.listReviewThreadComments("owner", "repo", 7L).complete());
        server.verify();
    }

    @Test
    void listPullRequestComments_marksMissingIssueBodyIncomplete() {
        RestClient.Builder builder = RestClient.builder().baseUrl(CREDS.baseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GiteaApiClient client = new GiteaApiClient(builder.build(), CREDS);
        server.expect(requestTo(CREDS.baseUrl() + "/api/v1/repos/owner/repo/issues/7/comments"))
                .andRespond(withSuccess());
        for (int i = 0; i < 2; i++) {
            server.expect(requestTo(CREDS.baseUrl() + "/api/v1/repos/owner/repo/pulls/7/reviews?limit=50&page=1"))
                    .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        }
        assertFalse(client.listPullRequestComments("owner", "repo", 7L).complete());
        server.verify();
    }

    @Test
    void listReviewThreadComments_marksMissingReviewPageIncomplete() {
        RestClient.Builder builder = RestClient.builder().baseUrl(CREDS.baseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GiteaApiClient client = new GiteaApiClient(builder.build(), CREDS);
        server.expect(requestTo(CREDS.baseUrl() + "/api/v1/repos/owner/repo/pulls/7/reviews?limit=50&page=1"))
                .andRespond(withSuccess());
        assertFalse(client.listReviewThreadComments("owner", "repo", 7L).complete());
        server.verify();
    }

    @Test
    void listReviewThreadComments_marksFailedReviewCommentScanIncomplete() {
        RestClient.Builder builder = RestClient.builder().baseUrl(CREDS.baseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GiteaApiClient client = new GiteaApiClient(builder.build(), CREDS);
        server.expect(requestTo(CREDS.baseUrl() + "/api/v1/repos/owner/repo/pulls/7/reviews?limit=50&page=1"))
                .andRespond(withSuccess("[{\"id\":52}]", MediaType.APPLICATION_JSON));
        server.expect(requestTo(CREDS.baseUrl() + "/api/v1/repos/owner/repo/pulls/7/reviews/52/comments"))
                .andRespond(withServerError());
        assertFalse(client.listReviewThreadComments("owner", "repo", 7L).complete());
        server.verify();
    }

}
