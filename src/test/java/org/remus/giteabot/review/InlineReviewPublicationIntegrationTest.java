package org.remus.giteabot.review;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.gitea.GiteaApiClient;
import org.remus.giteabot.github.GitHubApiClient;
import org.remus.giteabot.repository.PostReviewAction;
import org.remus.giteabot.repository.model.RepositoryCredentials;
import org.remus.giteabot.repository.model.ReviewPublicationResult.Delivery;
import org.remus.giteabot.repository.model.ReviewPublicationResult.Status;
import org.remus.giteabot.repository.model.ReviewSnapshot;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Exercises the real parser, diff validation, publisher and provider serialization together against
 * mocked provider HTTP contracts, so native anchors are proven on the wire rather than on a mocked client.
 */
class InlineReviewPublicationIntegrationTest {

    private static final String DIFF = """
            diff --git a/src/A.java b/src/A.java
            --- a/src/A.java
            +++ b/src/A.java
            @@ -1,3 +1,3 @@
             line1
            -old2
            +new2
             line3
            """;

    private static final String AI_OUTPUT = """
            ```json
            {"summary": "Looks mostly fine.", "findings": [
              {"path": "src/A.java", "side": "new", "line": 2, "severity": "MEDIUM", "category": "bug",
               "body": "New bug"},
              {"path": "src/A.java", "side": "old", "line": 2, "severity": "LOW", "category": "cleanup",
               "body": "Removed check"},
              {"path": "src/A.java", "side": "new", "line": 99, "severity": "LOW", "category": "style",
               "body": "Far away"}
            ]}
            ```""";

    private static final ReviewSnapshot SNAPSHOT = new ReviewSnapshot("h1", "b1", "m1", DIFF, true);

    @Test
    void giteaReceivesNativeOldAndNewSideCommentsAndUnplaceableFindingsStayInTheSummary() {
        RepositoryCredentials creds =
                RepositoryCredentials.of("https://gitea.example.com", "https://gitea.example.com", "token");
        RestClient.Builder builder = RestClient.builder().baseUrl(creds.baseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GiteaApiClient client = new GiteaApiClient(builder.build(), creds);
        String pr = creds.baseUrl() + "/api/v1/repos/o/r/pulls/7";
        ReviewDocument document = parse();
        String newBody = ReviewPublicationService.inlineBody(document.findings().get(0));
        String oldBody = ReviewPublicationService.inlineBody(document.findings().get(1));

        server.expect(requestTo(pr + "/reviews?page=1&limit=50")).andRespond(json("[]"));
        server.expect(requestTo(pr)).andExpect(method(HttpMethod.GET))
                .andRespond(json("{\"head\":{\"sha\":\"h1\"},\"base\":{\"sha\":\"b1\"},\"merge_base\":\"m1\"}"));
        server.expect(requestTo(pr + "/reviews"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.event").value("REQUEST_CHANGES"))
                .andExpect(jsonPath("$.commit_id").value("h1"))
                .andExpect(jsonPath("$.body").value(allOf(containsString("Looks mostly fine."),
                        containsString("Far away"), not(containsString("New bug")),
                        not(containsString("Removed check")))))
                .andExpect(jsonPath("$.comments", hasSize(2)))
                .andExpect(jsonPath("$.comments[0].path").value("src/A.java"))
                .andExpect(jsonPath("$.comments[0].new_position").value(2))
                .andExpect(jsonPath("$.comments[0].old_position").doesNotExist())
                .andExpect(jsonPath("$.comments[1].old_position").value(2))
                .andExpect(jsonPath("$.comments[1].new_position").doesNotExist())
                .andRespond(json("{\"id\":99,\"state\":\"REQUEST_CHANGES\"}"));
        server.expect(requestTo(pr + "/reviews/99/comments")).andRespond(json(
                "[{\"id\":5,\"path\":\"src/A.java\",\"body\":\"" + escape(newBody) + "\",\"position\":2},"
                        + "{\"id\":6,\"path\":\"src/A.java\",\"body\":\"" + escape(oldBody)
                        + "\",\"position\":0,\"original_position\":2}]"));

        ReviewPublicationService.Outcome outcome = ReviewPublicationService.publish(client, "o", "r", 7L,
                SNAPSHOT, document, PostReviewAction.REQUEST_CHANGES, UnaryOperator.identity(), ReviewFence.NONE);

        server.verify();
        assertThat(outcome.inlineStatus()).isEqualTo(Status.PUBLISHED);
        assertThat(outcome.inlineConfirmed()).isEqualTo(2);
        assertThat(outcome.action()).isEqualTo(Delivery.CONFIRMED);
        assertThat(outcome.fallbackUsed()).isFalse();
    }

    @Test
    void githubReceivesOneCommitBoundReviewWithLeftAndRightSideComments() {
        RepositoryCredentials creds = RepositoryCredentials.of("https://api.github.com", "https://github.com", "t");
        RestClient.Builder builder = RestClient.builder().baseUrl(creds.baseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubApiClient client = new GitHubApiClient(builder.build(), creds);

        server.expect(requestTo("https://api.github.com/repos/o/r/pulls/7/reviews"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.commit_id").value("h1"))
                .andExpect(jsonPath("$.event").value("COMMENT"))
                .andExpect(jsonPath("$.body").value(allOf(containsString("Far away"),
                        not(containsString("New bug")))))
                .andExpect(jsonPath("$.comments", hasSize(2)))
                .andExpect(jsonPath("$.comments[0].side").value("RIGHT"))
                .andExpect(jsonPath("$.comments[0].line").value(2))
                .andExpect(jsonPath("$.comments[1].side").value("LEFT"))
                .andExpect(jsonPath("$.comments[1].line").value(2))
                .andRespond(json("{\"id\":123}"));

        ReviewPublicationService.Outcome outcome = ReviewPublicationService.publish(client, "o", "r", 7L,
                SNAPSHOT, parse(), PostReviewAction.NONE, UnaryOperator.identity(), ReviewFence.NONE);

        server.verify();
        assertThat(outcome.inlineStatus()).isEqualTo(Status.PUBLISHED);
        assertThat(outcome.delivered()).isTrue();
    }

    @Test
    void summaryOnlySnapshotFallsBackToOneOrdinaryReviewWithEveryFinding() {
        RepositoryCredentials creds = RepositoryCredentials.of("https://api.github.com", "https://github.com", "t");
        RestClient.Builder builder = RestClient.builder().baseUrl(creds.baseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubApiClient client = new GitHubApiClient(builder.build(), creds);

        server.expect(requestTo("https://api.github.com/repos/o/r/pulls/7/reviews"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.comments").doesNotExist())
                .andExpect(jsonPath("$.body").value(allOf(containsString("New bug"),
                        containsString("Removed check"), containsString("Far away"))))
                .andRespond(json("{\"id\":1}"));

        ReviewPublicationService.Outcome outcome = ReviewPublicationService.publish(client, "o", "r", 7L,
                ReviewSnapshot.summaryOnly(DIFF), parse(), PostReviewAction.NONE, UnaryOperator.identity(),
                ReviewFence.NONE);

        server.verify();
        assertThat(outcome.inlineStatus()).isEqualTo(Status.UNSUPPORTED);
        assertThat(outcome.delivered()).isTrue();
    }

    private static ReviewDocument parse() {
        ReviewOutputParser.Result result = ReviewOutputParser.parse(AI_OUTPUT);
        assertThat(result.document().structured()).isTrue();
        assertThat(result.document().findings()).hasSize(3);
        return result.document();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static org.springframework.test.web.client.ResponseCreator json(String body) {
        return withSuccess(body, MediaType.APPLICATION_JSON);
    }
}
