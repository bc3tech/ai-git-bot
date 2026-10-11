package org.remus.giteabot.prworkflow.agentreview;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.agent.loop.AgentRunContext;
import org.remus.giteabot.agent.session.AgentSessionRepository;
import org.remus.giteabot.agent.session.AgentSessionService;
import org.remus.giteabot.agent.shared.AgentJackson;
import org.remus.giteabot.agent.tools.ToolCatalog;
import org.remus.giteabot.agent.validation.ToolExecutionService;
import org.remus.giteabot.agent.validation.WorkspaceResult;
import org.remus.giteabot.agent.validation.WorkspaceService;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.ai.ChatTurn;
import org.remus.giteabot.ai.StopReason;
import org.remus.giteabot.ai.ToolCall;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.eventhook.EventHookPublisher;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.repository.PostReviewAction;
import org.remus.giteabot.repository.RepositoryApiClient;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentReviewCompletionTest {

    private static final Path WORKSPACE = Path.of("/tmp/agent-review-test");
    private static final String DIFF = """
            diff --git a/Example.java b/Example.java
            --- a/Example.java
            +++ b/Example.java
            @@ -1 +1 @@
            -old
            +new
            """;

    @Mock private AiClient aiClient;
    @Mock private RepositoryApiClient repositoryClient;
    @Mock private WorkspaceService workspaceService;
    @Mock private EventHookPublisher eventHooks;

    private AgentReviewService service;
    private WebhookPayload payload;

    @BeforeEach
    void setUp() throws Exception {
        payload = AgentJackson.mapper().readValue("""
                {"repository":{"name":"repo","owner":{"login":"owner"}},
                 "pull_request":{"number":1,"title":"Update example","head":{"ref":"feature"}}}
                """, WebhookPayload.class);
        AgentConfigProperties config = new AgentConfigProperties();
        config.getBudget().setMaxRounds(3);
        ToolCatalog catalog = new ToolCatalog(config);
        service = new AgentReviewService(
                new AgentReviewContext(repositoryClient, aiClient, "Review the change.", "bot",
                        null, null, Set.of("pr-diff"), 200_000),
                new AgentSessionService(mock(AgentSessionRepository.class)),
                new ToolExecutionService(config, catalog, workspaceService), catalog, workspaceService,
                config, null, new Bot(), eventHooks);
        lenient().when(aiClient.supportsNativeTools()).thenReturn(true);
        lenient().when(repositoryClient.getPullRequestDiff("owner", "repo", 1L)).thenReturn(DIFF);
        lenient().when(workspaceService.prepareWorkspace(repositoryClient, "owner", "repo", "feature", 1L))
                .thenReturn(WorkspaceResult.success(WORKSPACE));
    }

    @ParameterizedTest
    @EnumSource(StopReason.class)
    void emptyTurnDoesNotPublishAnEarlierExplorationMessage(StopReason reason) {
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(exploration(1), new ChatTurn("", List.of(), reason, 78_886, 16_384));

        AgentReviewService.ReviewResult reviewed = service.reviewPullRequest(payload, 1, false, null,
                new AgentReviewService.SeverityThresholds(null, null, null), 1L, null);

        assertThat(reviewed).isEqualTo(AgentReviewService.ReviewResult.FAILED);
        assertFailureNoticeOnly();
    }

    @Test
    void exhaustedBudgetDoesNotPublishExplorationText() {
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(exploration(1), exploration(2), exploration(3));

        AgentReviewService.ReviewResult reviewed = service.reviewPullRequest(payload, 1, false, null,
                new AgentReviewService.SeverityThresholds(null, null, null), 1L, null);

        assertThat(reviewed).isEqualTo(AgentReviewService.ReviewResult.FAILED);
        assertFailureNoticeOnly();
    }

    private void assertFailureNoticeOnly() {
        verify(repositoryClient, never()).postReview(anyString(), anyString(), anyLong(), anyString(), any());
        verify(repositoryClient, never()).postReviewComment(anyString(), anyString(), anyLong(), anyString());
        verify(repositoryClient).postPullRequestComment(eq("owner"), eq("repo"), eq(1L),
                argThat(body -> body.contains("failure") && body.contains("run diagnostics")
                        && !body.contains("Error details")));
        verifyNoInteractions(eventHooks);
        verify(workspaceService).cleanupWorkspace(WORKSPACE);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void legacyIncompleteTurnCannotPublishReviewOrClarification(boolean clarification) {
        when(aiClient.supportsNativeTools()).thenReturn(false);
        // A String projection loses the truncation signal; using it would publish this text.
        lenient().when(aiClient.chat(anyList(), any(), anyString(), isNull(), anyInt()))
                .thenReturn("A partial review");
        when(aiClient.chatWithTools(anyList(), any(), eq(List.of()), anyString(), isNull(), anyInt()))
                .thenReturn(new ChatTurn("A partial review", List.of(), StopReason.MAX_TOKENS, 100, 32));

        AgentReviewService.ReviewResult result = clarification
                ? service.answerClarification(payload, "Why was this changed?", 1)
                : service.reviewPullRequest(payload, 1, false, null,
                        new AgentReviewService.SeverityThresholds(null, null, null), 1L, null);

        assertThat(result).isEqualTo(AgentReviewService.ReviewResult.FAILED);
        assertFailureNoticeOnly();
        verify(aiClient, never()).chat(anyList(), any(), anyString(), isNull(), anyInt());
    }

    @ParameterizedTest
    @EnumSource(value = StopReason.class, names = {"MAX_TOKENS", "OTHER"})
    void failedTurnCannotExecuteToolsOrPublishPartialText(StopReason reason) {
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(new ChatTurn("A partial finding", exploration(1).toolCalls(), reason, 100, 32));
        List<AgentRunContext.ToolCallRecord> toolCalls = new ArrayList<>();

        AgentReviewService.ReviewResult reviewed = service.reviewPullRequest(payload, 1, true, "Review criteria",
                new AgentReviewService.SeverityThresholds(0, 0, 0), 1L, toolCalls::add);

        assertThat(reviewed).isEqualTo(AgentReviewService.ReviewResult.FAILED);
        assertThat(toolCalls).isEmpty();
        assertFailureNoticeOnly();
    }

    @Test
    void failedClarificationDoesNotPublishItsDiagnosticPayload() {
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(exploration(1), new ChatTurn("", List.of(), StopReason.MAX_TOKENS, 100, 32));

        assertThat(service.answerClarification(payload, "Why was this changed?", 1))
                .isEqualTo(AgentReviewService.ReviewResult.FAILED);

        assertFailureNoticeOnly();
    }

    @Test
    void providerRequestFailureKeepsProviderBoundaryAndSanitizesException() {
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenThrow(new IllegalStateException("token=secret\r\n/private/source.java"));

        AgentReviewService.DetailedResult result = service.reviewPullRequestDetailed(payload, 1, false, null,
                new AgentReviewService.SeverityThresholds(null, null, null), 1L, null);

        assertThat(result.result()).isEqualTo(AgentReviewService.ReviewResult.FAILED);
        assertThat(result.failure().category()).isEqualTo(ReviewDiagnostics.Category.PROVIDER_REQUEST_FAILURE);
        assertThat(result.failure().providerCallFailed()).isTrue();
        assertThat(result.noticeStatus()).isEqualTo(ReviewDiagnostics.NoticeStatus.DELIVERED);
        ArgumentCaptor<String> comment = ArgumentCaptor.forClass(String.class);
        verify(repositoryClient).postPullRequestComment(eq("owner"), eq("repo"), eq(1L), comment.capture());
        assertThat(comment.getValue()).contains("provider request failed")
                .doesNotContain("secret", "source.java", "IllegalStateException");
        verify(repositoryClient, never()).postReview(anyString(), anyString(), anyLong(), anyString(), any());
        verify(workspaceService).cleanupWorkspace(WORKSPACE);
    }

    @Test
    void diffFetchExceptionIsReportedAsSetupFailureWithoutExceptionText() {
        when(repositoryClient.getPullRequestDiff("owner", "repo", 1L))
                .thenThrow(new IllegalStateException("token=secret\r\n/private/source.java"));

        AgentReviewService.DetailedResult result = service.reviewPullRequestDetailed(payload, 1, false, null,
                new AgentReviewService.SeverityThresholds(null, null, null), 1L, null);

        assertThat(result.result()).isEqualTo(AgentReviewService.ReviewResult.FAILED);
        assertThat(result.failure().category()).isEqualTo(ReviewDiagnostics.Category.SETUP_FAILURE);
        assertThat(result.noticeStatus()).isEqualTo(ReviewDiagnostics.NoticeStatus.DELIVERED);
        ArgumentCaptor<String> comment = ArgumentCaptor.forClass(String.class);
        verify(repositoryClient).postPullRequestComment(eq("owner"), eq("repo"), eq(1L), comment.capture());
        assertThat(comment.getValue()).contains("setup-failure")
                .doesNotContain("secret", "source.java", "IllegalStateException");
        verifyNoInteractions(aiClient);
    }

    @Test
    void workspacePreparationFailureIsReportedWithoutLeakingWorkspaceError() {
        when(workspaceService.prepareWorkspace(repositoryClient, "owner", "repo", "feature", 1L))
                .thenReturn(WorkspaceResult.failure("private checkout path"));

        AgentReviewService.DetailedResult result = service.reviewPullRequestDetailed(payload, 1, false, null,
                new AgentReviewService.SeverityThresholds(null, null, null), 1L, null);

        assertThat(result.failure().category()).isEqualTo(ReviewDiagnostics.Category.SETUP_FAILURE);
        assertThat(result.noticeStatus()).isEqualTo(ReviewDiagnostics.NoticeStatus.DELIVERED);
        ArgumentCaptor<String> comment = ArgumentCaptor.forClass(String.class);
        verify(repositoryClient).postPullRequestComment(eq("owner"), eq("repo"), eq(1L), comment.capture());
        assertThat(comment.getValue()).contains("setup-failure")
                .doesNotContain("private checkout path");
        verifyNoInteractions(aiClient);
    }

    @Test
    void missingPullRequestIdentityRecordsNoticeUnavailableWithoutCallingClient() {
        AgentReviewService.DetailedResult result = service.reviewPullRequestDetailed(new WebhookPayload(),
                1, false, null, new AgentReviewService.SeverityThresholds(null, null, null), 1L, null);

        assertThat(result.result()).isEqualTo(AgentReviewService.ReviewResult.FAILED);
        assertThat(result.failure().category()).isEqualTo(ReviewDiagnostics.Category.SETUP_FAILURE);
        assertThat(result.noticeStatus()).isEqualTo(ReviewDiagnostics.NoticeStatus.UNAVAILABLE);
        assertThat(result.noticeReason()).isEqualTo("pull-request-identity-unavailable");
        verifyNoInteractions(repositoryClient, aiClient, workspaceService);
    }

    @Test
    void outputTokenFailureReportsProviderUsageSeparatelyFromRoundCounts() {
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(new ChatTurn("partial", List.of(), StopReason.MAX_TOKENS, 100, 32));

        AgentReviewService.DetailedResult result = service.reviewPullRequestDetailed(payload, 1, false, null,
                new AgentReviewService.SeverityThresholds(null, null, null), 1L, null);

        assertThat(result.failure().category()).isEqualTo(ReviewDiagnostics.Category.OUTPUT_TOKEN_LIMIT);
        assertThat(result.failure().outputTokenLimit()).isPositive();
        assertThat(result.failure().outputTokens()).isEqualTo(32L);
        assertThat(result.failure().totalTurns()).isEqualTo(1);
        assertThat(result.failure().contextRounds()).isZero();
        ArgumentCaptor<String> comment = ArgumentCaptor.forClass(String.class);
        verify(repositoryClient).postPullRequestComment(eq("owner"), eq("repo"), eq(1L), comment.capture());
        assertThat(comment.getValue()).contains("output-token limit", "reported usage 32")
                .doesNotContain("partial");
    }

    @Test
    void nativeToolRequestsCountContextTurnsButStillExhaustTheOverallLoopBudget() {
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(exploration(1), exploration(2), exploration(3));

        AgentReviewService.DetailedResult result = service.reviewPullRequestDetailed(payload, 1, false, null,
                new AgentReviewService.SeverityThresholds(null, null, null), 1L, null);

        assertThat(result.failure().category()).isEqualTo(ReviewDiagnostics.Category.LOOP_ROUND_LIMIT);
        assertThat(result.failure().totalTurns()).isEqualTo(3);
        assertThat(result.failure().contextRounds()).isEqualTo(3);
        assertThat(result.failure().contextLimit()).isNull();
        verify(aiClient, times(3)).chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt());
        assertFailureNoticeOnly();
    }

    @Test
    void publicationFailureGetsOneAccurateNoticeWithoutChangingFailedOutcome() {
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(ChatTurn.text("A completed review."));
        org.mockito.Mockito.doThrow(new IllegalStateException("secret endpoint"))
                .when(repositoryClient).postReview(eq("owner"), eq("repo"), eq(1L), anyString(),
                        eq(PostReviewAction.NONE));

        AgentReviewService.DetailedResult result = service.reviewPullRequestDetailed(payload, 1, false, null,
                new AgentReviewService.SeverityThresholds(null, null, null), 1L, null);

        assertThat(result.result()).isEqualTo(AgentReviewService.ReviewResult.FAILED);
        assertThat(result.failure().category()).isEqualTo(ReviewDiagnostics.Category.PUBLICATION_FAILURE);
        assertThat(result.noticeStatus()).isEqualTo(ReviewDiagnostics.NoticeStatus.DELIVERED);
        ArgumentCaptor<String> comment = ArgumentCaptor.forClass(String.class);
        verify(repositoryClient).postPullRequestComment(eq("owner"), eq("repo"), eq(1L), comment.capture());
        assertThat(comment.getValue()).contains("completed review could not be published")
                .doesNotContain("secret", "endpoint");
        verify(repositoryClient, times(1)).postPullRequestComment(anyString(), anyString(), anyLong(), anyString());
        verify(workspaceService).cleanupWorkspace(WORKSPACE);
    }

    @Test
    void noticeFailureIsRecordedWithoutMaskingTheOriginalFailureOrRetrying() {
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(new ChatTurn("", List.of(), StopReason.MAX_TOKENS, 100, 32));
        org.mockito.Mockito.doThrow(new IllegalStateException("private endpoint"))
                .when(repositoryClient).postPullRequestComment(eq("owner"), eq("repo"), eq(1L), anyString());

        AgentReviewService.DetailedResult result = service.reviewPullRequestDetailed(payload, 1, false, null,
                new AgentReviewService.SeverityThresholds(null, null, null), 1L, null);

        assertThat(result.failure().category()).isEqualTo(ReviewDiagnostics.Category.OUTPUT_TOKEN_LIMIT);
        assertThat(result.noticeStatus()).isEqualTo(ReviewDiagnostics.NoticeStatus.FAILED);
        assertThat(result.noticeReason()).isEqualTo("delivery-failed");
        verify(repositoryClient, times(1)).postPullRequestComment(anyString(), anyString(), anyLong(), anyString());
        verify(workspaceService).cleanupWorkspace(WORKSPACE);
    }

    @Test
    void completedTurnPublishesOnlyTheFinalReview() {
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(exploration(1), ChatTurn.text("No correctness issues found."));

        AgentReviewService.ReviewResult reviewed = service.reviewPullRequest(payload, 1, false, null,
                new AgentReviewService.SeverityThresholds(null, null, null), 1L, null);

        assertThat(reviewed).isEqualTo(AgentReviewService.ReviewResult.POSTED);
        verify(repositoryClient).postReview(eq("owner"), eq("repo"), eq(1L),
                argThat(body -> body.contains("No correctness issues found.") && !body.contains("Let me check")),
                eq(PostReviewAction.NONE));
        verify(workspaceService).cleanupWorkspace(WORKSPACE);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingDiffIsANoOpForReviewsAndClarifications(boolean clarification) {
        when(repositoryClient.getPullRequestDiff("owner", "repo", 1L)).thenReturn("");

        AgentReviewService.ReviewResult result = clarification
                ? service.answerClarification(payload, "Why was this changed?", 1)
                : service.reviewPullRequest(payload, 1, false, null,
                        new AgentReviewService.SeverityThresholds(null, null, null), 1L, null);

        assertThat(result).isEqualTo(AgentReviewService.ReviewResult.NO_DIFF);
        verifyNoInteractions(aiClient, workspaceService, eventHooks);
    }

    private ChatTurn exploration(int round) {
        return new ChatTurn("Let me check the remaining files...",
                List.of(new ToolCall("read-diff-" + round, "pr-diff",
                        AgentJackson.mapper().createObjectNode().put("path", "Example.java"))),
                StopReason.TOOL_USE, 100, 20);
    }
}
