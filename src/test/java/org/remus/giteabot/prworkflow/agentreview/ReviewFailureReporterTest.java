package org.remus.giteabot.prworkflow.agentreview;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.remus.giteabot.repository.RepositoryApiClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doThrow;

class ReviewFailureReporterTest {

    @ParameterizedTest
    @EnumSource(ReviewDiagnostics.Category.class)
    void eachCategoryPublishesOnePlainSafeNotice(ReviewDiagnostics.Category category) {
        RepositoryApiClient client = mock(RepositoryApiClient.class);
        var failure = snapshot(category);

        var delivery = new ReviewFailureReporter().report(client, "owner", "repo", 9L, failure, false);

        assertThat(delivery.status()).isEqualTo(ReviewDiagnostics.NoticeStatus.DELIVERED);
        verify(client, times(1)).postPullRequestComment(eq("owner"), eq("repo"), eq(9L),
                contains("AI Agent (Review) failure"));
        verify(client, never()).postReview(anyString(), anyString(), anyLong(), anyString(), any());
        verify(client, never()).postReviewComment(anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void publicationFailureNoticeDoesNotClaimAnalysisWasIncomplete() {
        RepositoryApiClient client = mock(RepositoryApiClient.class);

        new ReviewFailureReporter().report(client, "owner", "repo", 9L,
                snapshot(ReviewDiagnostics.Category.PUBLICATION_FAILURE), false);

        verify(client).postPullRequestComment(eq("owner"), eq("repo"), eq(9L),
                contains("The completed review could not be published"));
    }

    @Test
    void failedDeliveryDoesNotLeakExceptionTextOrRetry() {
        RepositoryApiClient client = mock(RepositoryApiClient.class);
        doThrow(new IllegalStateException("token=secret\r\n/private/source.java"))
                .when(client).postPullRequestComment(anyString(), anyString(), anyLong(), anyString());

        var result = new ReviewFailureReporter().report(client, "owner", "repo", 9L,
                snapshot(ReviewDiagnostics.Category.CONTEXT_ROUND_LIMIT), false);

        assertThat(result.status()).isEqualTo(ReviewDiagnostics.NoticeStatus.FAILED);
        assertThat(result.reason()).isEqualTo("delivery-failed");
        verify(client, times(1)).postPullRequestComment(anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void unavailableIdentityOrClientIsRecordedWithoutCallingRepository() {
        RepositoryApiClient client = mock(RepositoryApiClient.class);
        var reporter = new ReviewFailureReporter();
        var failure = snapshot(ReviewDiagnostics.Category.SETUP_FAILURE);

        var missingIdentity = reporter.report(client, null, "repo", 9L, failure, false);
        var missingClient = reporter.report(null, "owner", "repo", 9L, failure, false);

        assertThat(missingIdentity.reason()).isEqualTo("pull-request-identity-unavailable");
        assertThat(missingClient.reason()).isEqualTo("repository-client-unavailable");
        assertThat(missingIdentity.status()).isEqualTo(ReviewDiagnostics.NoticeStatus.UNAVAILABLE);
        assertThat(missingClient.status()).isEqualTo(ReviewDiagnostics.NoticeStatus.UNAVAILABLE);
        verifyNoInteractions(client);
    }

    private static ReviewDiagnostics.Snapshot snapshot(ReviewDiagnostics.Category category) {
        return new ReviewDiagnostics.Collector(10, 20, 4096,
                3L, "owner", "repo", 9L, "llamacpp", 4L).snapshot(category);
    }
}
