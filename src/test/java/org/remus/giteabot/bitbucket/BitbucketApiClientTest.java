package org.remus.giteabot.bitbucket;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.repository.PostReviewAction;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.model.ReviewPublicationResult;
import org.remus.giteabot.repository.model.RepositoryCredentials;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Unit tests for {@link BitbucketApiClient} verifying that it correctly implements
 * {@link RepositoryApiClient} and exposes the expected base URL, clone URL, and token.
 */
class BitbucketApiClientTest {

    private static RepositoryCredentials credsWithUsername() {
        return RepositoryCredentials.of(
                "https://api.bitbucket.org/2.0", "https://bitbucket.org", "myuser", "bb_token");
    }

    private static RepositoryCredentials creds() {
        return RepositoryCredentials.of(
                "https://api.bitbucket.org/2.0", "https://bitbucket.org", "bb_token");
    }

    @Test
    void implementsRepositoryApiClient() {
        BitbucketApiClient client = new BitbucketApiClient(null, creds());
        assertInstanceOf(RepositoryApiClient.class, client);
    }

    @Test
    void getBaseUrl_returnsConfiguredUrl() {
        BitbucketApiClient client = new BitbucketApiClient(null, creds());
        assertEquals("https://api.bitbucket.org/2.0", client.getBaseUrl());
    }

    @Test
    void getCloneUrl_returnsConfiguredUrl() {
        BitbucketApiClient client = new BitbucketApiClient(null, creds());
        assertEquals("https://bitbucket.org", client.getCloneUrl());
    }

    @Test
    void getToken_returnsConfiguredToken() {
        BitbucketApiClient client = new BitbucketApiClient(null, creds());
        assertEquals("bb_token", client.getToken());
    }

    @Test
    void addReaction_noOp() {
        // Bitbucket doesn't support reactions; verify no exception is thrown
        BitbucketApiClient client = new BitbucketApiClient(null, creds());
        assertDoesNotThrow(() -> client.addReaction("workspace", "repo", 1L, "+1"));
    }

    @Test
    void getCredentials_returnsUsername() {
        BitbucketApiClient client = new BitbucketApiClient(null, credsWithUsername());
        assertEquals("myuser", client.getCredentials().username());
        assertTrue(client.getCredentials().hasUsername());
    }

    @Test
    void getIssueComments_fetchesIssueCommentsWithPageLength() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.bitbucket.org/2.0");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BitbucketApiClient client = new BitbucketApiClient(builder.build(), creds());

        server.expect(requestTo(
                        "https://api.bitbucket.org/2.0/repositories/workspace/repo/issues/42/comments?pagelen=50"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"values\":[{\"id\":301,\"content\":{\"raw\":\"First comment\"}}]}",
                        MediaType.APPLICATION_JSON));

        List<Map<String, Object>> comments = client.getIssueComments("workspace", "repo", 42L);

        server.verify();
        assertEquals(1, comments.size());
        assertEquals(301, ((Number) comments.getFirst().get("id")).intValue());
        assertInstanceOf(Map.class, comments.getFirst().get("content"));
    }

    @Test
    void getReviewSnapshot_isSummaryOnlyAndInlinePublicationIsUnsupportedWithoutWrites() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.bitbucket.org/2.0");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BitbucketApiClient client = new BitbucketApiClient(builder.build(), creds());
        server.expect(requestTo(
                        "https://api.bitbucket.org/2.0/repositories/workspace/repo/pullrequests/7/diff"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("diff --git a/a b/a\n", MediaType.TEXT_PLAIN));

        var snapshot = client.getReviewSnapshot("workspace", "repo", 7L);
        ReviewPublicationResult result = client.publishInlineReview("workspace", "repo", 7L,
                snapshot, "Summary", List.of(), PostReviewAction.NONE);

        server.verify();
        assertEquals("diff --git a/a b/a\n", snapshot.diff());
        assertFalse(snapshot.inlineSupported());
        assertEquals(ReviewPublicationResult.Status.UNSUPPORTED, result.status());
    }

    @Test
    void replyToReviewThread_usesParentAndReturnsRemoteId() {
        RestClient.Builder builder = RestClient.builder().baseUrl(creds().baseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BitbucketApiClient client = new BitbucketApiClient(builder.build(), creds());
        server.expect(requestTo(creds().baseUrl() + "/repositories/workspace/repo/pullrequests/7/comments"))
                .andExpect(method(HttpMethod.POST)).andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.parent.id").value(123))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.content.raw").value("reply"))
                .andRespond(withSuccess("{\"id\":456}", MediaType.APPLICATION_JSON));
        assertEquals(java.util.Optional.of("456"),
                client.replyToReviewThread("workspace", "repo", 7L, "123", "reply"));
        server.verify();
    }

    @Test
    void listReviewThreadComments_preservesParentIdentity() {
        RestClient.Builder builder = RestClient.builder().baseUrl(creds().baseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BitbucketApiClient client = new BitbucketApiClient(builder.build(), creds());
        server.expect(requestTo(creds().baseUrl() + "/repositories/workspace/repo/pullrequests/7/comments?pagelen=50"))
                .andRespond(withSuccess("{\"values\":[{\"id\":456,\"parent\":{\"id\":123},\"created_on\":\"2026-10-08T10:00:00Z\",\"content\":{\"raw\":\"reply\"}}]}",
                        MediaType.APPLICATION_JSON));
        var scan = client.listReviewThreadComments("workspace", "repo", 7L);
        assertTrue(scan.complete());
        assertEquals("123", scan.comments().getFirst().getThreadId());
        assertEquals("123", scan.comments().getFirst().getParentId());
        server.verify();
    }


    @Test
    void listPullRequestComments_followsNextUrlAndReportsComplete() {
        RestClient.Builder builder = RestClient.builder().baseUrl(creds().baseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BitbucketApiClient client = new BitbucketApiClient(builder.build(), creds());
        String firstPage = "{\"values\":[" + String.join(",", java.util.Collections.nCopies(50,
                "{\"id\":1,\"content\":{\"raw\":\"comment\"}}"))
                + "],\"next\":\"https://api.bitbucket.org/2.0/repositories/workspace/repo/pullrequests/7/comments?pagelen=50&page=2\"}";
        server.expect(requestTo(creds().baseUrl() + "/repositories/workspace/repo/pullrequests/7/comments?pagelen=50"))
                .andRespond(withSuccess(firstPage, MediaType.APPLICATION_JSON));
        server.expect(requestTo(creds().baseUrl() + "/repositories/workspace/repo/pullrequests/7/comments?pagelen=50&page=2"))
                .andRespond(withSuccess("{\"values\":[{\"id\":51,\"content\":{\"raw\":\"last\"}}]}", MediaType.APPLICATION_JSON));
        var scan = client.listPullRequestComments("workspace", "repo", 7L);
        assertTrue(scan.complete());
        assertEquals(51, scan.comments().size());
        server.verify();
    }

    @Test
    void listPullRequestComments_pageFailureIsIncomplete() {
        RestClient.Builder builder = RestClient.builder().baseUrl(creds().baseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BitbucketApiClient client = new BitbucketApiClient(builder.build(), creds());
        String firstPage = "{\"values\":[" + String.join(",", java.util.Collections.nCopies(50,
                "{\"id\":1,\"content\":{\"raw\":\"comment\"}}"))
                + "],\"next\":\"https://api.bitbucket.org/2.0/repositories/workspace/repo/pullrequests/7/comments?pagelen=50&page=2\"}";
        server.expect(requestTo(creds().baseUrl() + "/repositories/workspace/repo/pullrequests/7/comments?pagelen=50"))
                .andRespond(withSuccess(firstPage, MediaType.APPLICATION_JSON));
        server.expect(requestTo(creds().baseUrl() + "/repositories/workspace/repo/pullrequests/7/comments?pagelen=50&page=2"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        var scan = client.listPullRequestComments("workspace", "repo", 7L);
        assertFalse(scan.complete());
        assertEquals(50, scan.comments().size());
        server.verify();
    }

    @Test
    void replyNotFoundIsSurfacedForCallerFallback() {
        RestClient.Builder builder = RestClient.builder().baseUrl(creds().baseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BitbucketApiClient client = new BitbucketApiClient(builder.build(), creds());
        server.expect(requestTo(creds().baseUrl() + "/repositories/workspace/repo/pullrequests/7/comments"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withStatus(HttpStatus.NOT_FOUND));
        assertThrows(org.springframework.web.client.HttpClientErrorException.class,
                () -> client.replyToReviewThread("workspace", "repo", 7L, "12", "reply"));
        server.verify();
    }

}
