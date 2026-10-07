package org.remus.giteabot.gitea;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.repository.PostReviewAction;
import org.remus.giteabot.repository.model.DiffSide;
import org.remus.giteabot.repository.model.RepositoryCredentials;
import org.remus.giteabot.repository.model.ReviewAnchorComment;
import org.remus.giteabot.repository.model.ReviewPublicationResult;
import org.remus.giteabot.repository.model.ReviewPublicationResult.Delivery;
import org.remus.giteabot.repository.model.ReviewPublicationResult.Status;
import org.remus.giteabot.repository.model.ReviewSnapshot;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GiteaInlineReviewPublisherTest {

    private static final RepositoryCredentials CREDS =
            RepositoryCredentials.of("https://gitea.example.com", "https://gitea.example.com", "token");
    private static final String PR = CREDS.baseUrl() + "/api/v1/repos/o/r/pulls/7";
    private static final String REVIEWS = PR + "/reviews";
    private static final String LIST = REVIEWS + "?page=1&limit=50";
    private static final String DETAILS = "{\"head\":{\"sha\":\"h1\"},\"base\":{\"sha\":\"b1\"},\"merge_base\":\"m1\"}";
    private static final ReviewSnapshot SNAPSHOT = new ReviewSnapshot("h1", "b1", "m1", "diff", true);

    private MockRestServiceServer server;
    private GiteaApiClient client;

    private final ReviewAnchorComment added =
            new ReviewAnchorComment("f1", "src/A.java", null, DiffSide.NEW, 12, null, 12, "new-side note");
    private final ReviewAnchorComment removed =
            new ReviewAnchorComment("f2", "src/A.java", null, DiffSide.OLD, 4, 4, null, "old-side note");

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(CREDS.baseUrl());
        server = MockRestServiceServer.bindTo(builder).build();
        client = new GiteaApiClient(builder.build(), CREDS);
    }

    @Test
    void snapshotCapturesHeadAndMergeBaseWhenStable() {
        expectDetails(DETAILS);
        server.expect(requestTo(PR + ".diff")).andRespond(withSuccess("the-diff", MediaType.TEXT_PLAIN));
        expectDetails(DETAILS);

        ReviewSnapshot snapshot = client.getReviewSnapshot("o", "r", 7L);

        assertThat(snapshot.inlineSupported()).isTrue();
        assertThat(snapshot.headSha()).isEqualTo("h1");
        assertThat(snapshot.startSha()).isEqualTo("m1");
        assertThat(snapshot.diff()).isEqualTo("the-diff");
    }

    @Test
    void snapshotFallsBackToSummaryOnlyWhenHeadKeepsMoving() {
        for (int i = 0; i < 2; i++) {
            expectDetails("{\"head\":{\"sha\":\"h" + i + "\"},\"base\":{\"sha\":\"b\"}}");
            server.expect(requestTo(PR + ".diff")).andRespond(withSuccess("d" + i, MediaType.TEXT_PLAIN));
            expectDetails("{\"head\":{\"sha\":\"moved" + i + "\"},\"base\":{\"sha\":\"b\"}}");
        }

        ReviewSnapshot snapshot = client.getReviewSnapshot("o", "r", 7L);

        assertThat(snapshot.inlineSupported()).isFalse();
        assertThat(snapshot.diff()).isEqualTo("d1");
    }

    @Test
    void publishesSingleReviewAndVerifiesPlacement() {
        server.expect(requestTo(LIST)).andRespond(json("[{\"id\":1,\"state\":\"COMMENT\",\"body\":\"old\"}]"));
        expectDetails(DETAILS);
        server.expect(ExpectedCount.once(), requestTo(REVIEWS))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.event").value("REQUEST_CHANGES"))
                .andExpect(jsonPath("$.commit_id").value("h1"))
                .andExpect(jsonPath("$.body").value("summary"))
                .andExpect(jsonPath("$.comments[0].path").value("src/A.java"))
                .andExpect(jsonPath("$.comments[0].new_position").value(12))
                .andExpect(jsonPath("$.comments[0].old_position").doesNotExist())
                .andExpect(jsonPath("$.comments[1].old_position").value(4))
                .andExpect(jsonPath("$.comments[1].new_position").doesNotExist())
                .andRespond(json("{\"id\":99,\"state\":\"REQUEST_CHANGES\"}"));
        server.expect(requestTo(REVIEWS + "/99/comments")).andRespond(json("""
                [{"id":5,"path":"src/A.java","body":"new-side note","position":12,"original_position":0},
                 {"id":6,"path":"src/A.java","body":"old-side note","position":0,"original_position":4}]
                """));

        ReviewPublicationResult result = publish(PostReviewAction.REQUEST_CHANGES);

        server.verify();
        assertThat(result.status()).isEqualTo(Status.PUBLISHED);
        assertThat(result.review()).isEqualTo(Delivery.CONFIRMED);
        assertThat(result.comments()).extracting(ReviewPublicationResult.CommentOutcome::remoteId)
                .containsExactly("5", "6");
    }

    @Test
    void readBackReportsMisplacedAndMissingComments() {
        server.expect(requestTo(LIST)).andRespond(json("[]"));
        expectDetails(DETAILS);
        server.expect(requestTo(REVIEWS)).andRespond(json("{\"id\":99}"));
        server.expect(requestTo(REVIEWS + "/99/comments")).andRespond(json(
                "[{\"id\":5,\"path\":\"src/A.java\",\"body\":\"new-side note\",\"position\":13}]"));

        ReviewPublicationResult result = publish(PostReviewAction.NONE);

        assertThat(result.status()).isEqualTo(Status.PARTIAL);
        assertThat(result.comments()).extracting(ReviewPublicationResult.CommentOutcome::delivery)
                .containsExactly(Delivery.MISPLACED, Delivery.FAILED);
        assertThat(result.nothingWritten()).isFalse();
    }

    @Test
    void pendingReviewIsAConflictAndNothingIsWritten() {
        server.expect(requestTo(LIST)).andRespond(json("[{\"id\":3,\"state\":\"PENDING\"}]"));

        ReviewPublicationResult result = publish(PostReviewAction.APPROVE);

        server.verify();
        assertThat(result.status()).isEqualTo(Status.PENDING_CONFLICT);
        assertThat(result.nothingWritten()).isTrue();
    }

    @Test
    void movedHeadIsStaleAndNothingIsWritten() {
        server.expect(requestTo(LIST)).andRespond(json("[]"));
        expectDetails("{\"head\":{\"sha\":\"h2\"},\"base\":{\"sha\":\"b1\"},\"merge_base\":\"m1\"}");

        ReviewPublicationResult result = publish(PostReviewAction.NONE);

        server.verify();
        assertThat(result.status()).isEqualTo(Status.STALE_SNAPSHOT);
    }

    @Test
    void clientErrorWithoutCreatedReviewIsRejected() {
        server.expect(requestTo(LIST)).andRespond(json("[]"));
        expectDetails(DETAILS);
        server.expect(requestTo(REVIEWS)).andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY));
        server.expect(requestTo(LIST)).andRespond(json("[]"));

        ReviewPublicationResult result = publish(PostReviewAction.NONE);

        server.verify();
        assertThat(result.status()).isEqualTo(Status.REJECTED);
        assertThat(result.nothingWritten()).isTrue();
    }

    @Test
    void serverErrorIsUnknownButVerifiesAReviewThatWasCreated() {
        server.expect(requestTo(LIST)).andRespond(json("[{\"id\":1,\"state\":\"COMMENT\",\"body\":\"summary\"}]"));
        expectDetails(DETAILS);
        server.expect(requestTo(REVIEWS)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        server.expect(requestTo(LIST)).andRespond(json(
                "[{\"id\":1,\"state\":\"COMMENT\",\"body\":\"summary\"},{\"id\":2,\"state\":\"COMMENT\",\"body\":\"summary\"}]"));
        server.expect(requestTo(REVIEWS + "/2/comments")).andRespond(json("""
                [{"id":5,"path":"src/A.java","body":"new-side note","position":12},
                 {"id":6,"path":"src/A.java","body":"old-side note","original_position":4}]
                """));

        ReviewPublicationResult result = publish(PostReviewAction.NONE);

        server.verify();
        assertThat(result.status()).isEqualTo(Status.PUBLISHED);
    }

    @Test
    void serverErrorWithNothingVisibleIsUnknown() {
        server.expect(requestTo(LIST)).andRespond(json("[]"));
        expectDetails(DETAILS);
        server.expect(requestTo(REVIEWS)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        server.expect(requestTo(LIST)).andRespond(json("[]"));

        ReviewPublicationResult result = publish(PostReviewAction.NONE);

        assertThat(result.status()).isEqualTo(Status.UNKNOWN);
        assertThat(result.nothingWritten()).isFalse();
    }

    @Test
    void summaryOnlySnapshotIsUnsupported() {
        ReviewPublicationResult result = client.publishInlineReview("o", "r", 7L,
                ReviewSnapshot.summaryOnly("d"), "summary", List.of(added), PostReviewAction.NONE);
        assertThat(result.status()).isEqualTo(Status.UNSUPPORTED);
        server.verify();
    }

    private ReviewPublicationResult publish(PostReviewAction action) {
        return client.publishInlineReview("o", "r", 7L, SNAPSHOT, "summary", List.of(added, removed), action);
    }

    private void expectDetails(String body) {
        server.expect(requestTo(PR)).andExpect(method(HttpMethod.GET)).andRespond(json(body));
    }

    private static org.springframework.test.web.client.ResponseCreator json(String body) {
        return withSuccess(body, MediaType.APPLICATION_JSON);
    }
}
