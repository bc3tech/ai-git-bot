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
import org.remus.giteabot.prworkflow.WorkflowCancelledException;
import org.remus.giteabot.repository.PostReviewAction;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.model.ReviewAnchorComment;
import org.remus.giteabot.repository.model.ReviewPublicationResult;
import org.remus.giteabot.repository.model.ReviewSnapshot;
import org.remus.giteabot.review.ReviewFence;
import org.remus.giteabot.review.ReviewSkipService;
import org.remus.giteabot.review.grounding.*;
import org.remus.giteabot.review.grounding.DoubleCheckService.*;
import org.remus.giteabot.review.state.*;
import org.remus.giteabot.review.state.ReviewRunLeaseService.LeaseToken;
import org.remus.giteabot.audit.PrAuditEventService;
import org.remus.giteabot.audit.AuditEventType;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;

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

    private static final Path WORKSPACE = Path.of("agent-review-test").toAbsolutePath();
    private static final String DIFF = """
            diff --git a/Example.java b/Example.java
            --- a/Example.java
            +++ b/Example.java
            @@ -1 +1 @@
            -old
            +newCall();
            """;

    @Mock private AiClient aiClient;
    @Mock private RepositoryApiClient repositoryClient;
    @Mock private WorkspaceService workspaceService;
    @Mock private EventHookPublisher eventHooks;
    @Mock private ReviewRunLeaseService leases;
    @Mock private ReviewStateService state;
    @Mock private PriorFindingsService priors;
    @Mock private ReviewSkipService skipService;
    @Mock private PrAuditEventService audits;
    @Mock private DoubleCheckService doubleCheck;
    @Mock private ThreadActionExecutor threadActions;
    private static final String HEAD = "a".repeat(40);
    private static final String BASE = "b".repeat(40);
    private static final LeaseToken LEASE = new LeaseToken(9L, "owner", "repo", 1L, "holder", 1);
    private final PriorFindingsDigest emptyDigest = new PriorFindingsDigest(List.of(), true,
            new PriorFindingsDigest.CommentCursor(Instant.parse("2026-10-08T00:00:00Z"), "100"));

    private AgentReviewService service;
    private AgentConfigProperties config;
    private WebhookPayload payload;

    @BeforeEach
    void setUp() throws Exception {
        payload = AgentJackson.mapper().readValue("""
                {"repository":{"name":"repo","owner":{"login":"owner"}},
                 "pull_request":{"number":1,"title":"Update example","head":{"ref":"feature"}}}
                """, WebhookPayload.class);
        config = new AgentConfigProperties();
        config.getBudget().setMaxRounds(3);
        ToolCatalog catalog = new ToolCatalog(config);
        Bot bot = new Bot();
        bot.setId(9L);
        service = new AgentReviewService(
                new AgentReviewContext(repositoryClient, aiClient, "Review the change.", "bot",
                        null, null, Set.of("pr-diff"), 200_000),
                new AgentSessionService(mock(AgentSessionRepository.class)),
                new ToolExecutionService(config, catalog, workspaceService), catalog, workspaceService,
                config, null, bot, eventHooks, leases, state, priors,
                new FindingGroundingService(doubleCheck, audits), threadActions, skipService, audits,
                Duration.ofMinutes(10), DoubleCheckSettings.defaults(), new Budget(60_000, 20));
        lenient().when(leases.acquire(9L, "owner", "repo", 1L)).thenReturn(LEASE);
        lenient().when(priors.load(repositoryClient, LEASE, AgentReviewWorkflow.KEY, "bot")).thenReturn(emptyDigest);
        lenient().when(repositoryClient.getReviewSnapshot("owner", "repo", 1L))
                .thenReturn(new ReviewSnapshot(HEAD, BASE, BASE, DIFF, true));
        lenient().when(repositoryClient.getFileContent("owner", "repo", "Example.java", HEAD)).thenReturn("newCall();");
        lenient().when(doubleCheck.check(anyList(), any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            List<Candidate> candidates = inv.getArgument(0);
            return candidates.stream().collect(Collectors.toMap(c -> c.finding().id(),
                    c -> new DoubleCheckOutcome(Verdict.KEEP, "Checked")));
        });
        lenient().when(workspaceService.prepareWorkspaceAtRevision(repositoryClient, "owner", "repo", HEAD, 1L))
                .thenReturn(WorkspaceResult.success(WORKSPACE));
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void diffFetchExceptionIsReportedAsSetupFailureWithoutExceptionText(boolean snapshotThrows) {
        if (snapshotThrows) {
            when(repositoryClient.getReviewSnapshot("owner", "repo", 1L))
                    .thenThrow(new IllegalStateException("token=secret"));
        } else {
            when(repositoryClient.getReviewSnapshot("owner", "repo", 1L)).thenReturn(null);
        }
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
        verify(leases).release(LEASE);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void workspacePreparationFailureIsReportedWithoutLeakingWorkspaceError(boolean throwsException) {
        if (throwsException) {
            when(workspaceService.prepareWorkspaceAtRevision(repositoryClient, "owner", "repo", HEAD, 1L))
                    .thenThrow(new IllegalStateException("private checkout path"));
        } else {
            when(workspaceService.prepareWorkspaceAtRevision(repositoryClient, "owner", "repo", HEAD, 1L))
                    .thenReturn(WorkspaceResult.failure("private checkout path"));
        }

        AgentReviewService.DetailedResult result = service.reviewPullRequestDetailed(payload, 1, false, null,
                new AgentReviewService.SeverityThresholds(null, null, null), 1L, null);

        assertThat(result.result()).isEqualTo(AgentReviewService.ReviewResult.FAILED);
        assertThat(result.failure().category()).isEqualTo(ReviewDiagnostics.Category.SETUP_FAILURE);
        assertThat(result.noticeStatus()).isEqualTo(ReviewDiagnostics.NoticeStatus.DELIVERED);
        ArgumentCaptor<String> comment = ArgumentCaptor.forClass(String.class);
        verify(repositoryClient).postPullRequestComment(eq("owner"), eq("repo"), eq(1L), comment.capture());
        assertThat(comment.getValue()).contains("setup-failure")
                .doesNotContain("private checkout path");
        verifyNoInteractions(aiClient);
        verify(leases).release(LEASE);
        verify(state, never()).upsertRevision(any(), any());
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
        when(repositoryClient.postReviewReturningId(eq("owner"), eq("repo"), eq(1L), anyString(),
                eq(PostReviewAction.NONE))).thenThrow(new IllegalStateException("secret endpoint"));

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
        verify(repositoryClient, times(1)).postReviewReturningId(any(), any(), any(), any(), any());
        verify(state, never()).upsertRevision(any(), any());
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
                .thenReturn(exploration(1), ChatTurn.text("{\"summary\": \"No correctness issues found.\", \"findings\": []}"));

        AgentReviewService.ReviewResult reviewed = service.reviewPullRequest(payload, 1, false, null,
                new AgentReviewService.SeverityThresholds(null, null, null), 1L, null);

        assertThat(reviewed).isEqualTo(AgentReviewService.ReviewResult.POSTED);
        verify(repositoryClient).postReviewReturningId(eq("owner"), eq("repo"), eq(1L),
                argThat(body -> body.contains("No correctness issues found.") && !body.contains("Let me check")),
                eq(PostReviewAction.NONE));
        verify(workspaceService).cleanupWorkspace(WORKSPACE);
    }

    @Test
    void structuredReviewWithDecisionPublishesInlineWithTheFormalAction() {
        when(repositoryClient.getReviewSnapshot("owner", "repo", 1L))
                .thenReturn(new ReviewSnapshot(HEAD, BASE, BASE, DIFF, true));
        String finalReview = """
                ```json
                {"summary": "One problem.", "findings": [
                  {"path": "Example.java", "side": "new", "line": 1, "severity": "BLOCKER", "quote": "newCall();", "body": "Broken."}
                ]}
                ```
                {"blocker": 1, "medium": 0, "low": 0}""";
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(ChatTurn.text(finalReview));
        when(repositoryClient.publishInlineReview(any(), any(), any(), any(), anyString(), anyList(), any()))
                .thenAnswer(inv -> {
                    List<ReviewAnchorComment> anchors = inv.getArgument(5);
                    return new ReviewPublicationResult(ReviewPublicationResult.Status.PUBLISHED,
                            ReviewPublicationResult.Delivery.CONFIRMED,
                            anchors.stream().map(a -> new ReviewPublicationResult.CommentOutcome(a,
                                    ReviewPublicationResult.Delivery.CONFIRMED, "1")).toList(), null);
                });

        AgentReviewService.ReviewResult reviewed = service.reviewPullRequest(payload, 1, true, "Review criteria",
                new AgentReviewService.SeverityThresholds(0, null, null), 1L, null);

        assertThat(reviewed).isEqualTo(AgentReviewService.ReviewResult.POSTED);
        verify(repositoryClient).publishInlineReview(eq("owner"), eq("repo"), eq(1L), any(),
                argThat(body -> body.contains("One problem.") && !body.contains("Broken.")
                        && !body.contains("\"blocker\"")),
                argThat(anchors -> anchors.size() == 1 && anchors.getFirst().path().equals("Example.java")),
                eq(PostReviewAction.REQUEST_CHANGES));
        verify(repositoryClient, never()).postReviewAction(anyString(), anyString(), anyLong(), any());
        verify(repositoryClient, never()).postReview(anyString(), anyString(), anyLong(), anyString(), any());
    }

    @Test
    void supersededReviewPropagatesWithoutPostingAnErrorComment() {
        org.junit.jupiter.api.Assertions.assertThrows(WorkflowCancelledException.class,
                () -> service.reviewPullRequest(payload, 1, false, null,
                        new AgentReviewService.SeverityThresholds(null, null, null), 1L, null,
                        location -> {
                            throw new WorkflowCancelledException("superseded");
                        }));

        verify(repositoryClient, never()).postReview(anyString(), anyString(), anyLong(), anyString(), any());
        verify(repositoryClient, never()).postPullRequestComment(anyString(), anyString(), anyLong(), anyString());
        verifyNoInteractions(workspaceService);
        verify(leases, never()).acquire(any(), any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingDiffIsANoOpForReviewsAndClarifications(boolean clarification) {
        lenient().when(repositoryClient.getPullRequestDiff("owner", "repo", 1L)).thenReturn("");
        lenient().when(repositoryClient.getReviewSnapshot("owner", "repo", 1L)).thenReturn(ReviewSnapshot.summaryOnly(""));

        AgentReviewService.ReviewResult result = clarification
                ? service.answerClarification(payload, "Why was this changed?", 1)
                : service.reviewPullRequest(payload, 1, false, null,
                        new AgentReviewService.SeverityThresholds(null, null, null), 1L, null);

        assertThat(result).isEqualTo(AgentReviewService.ReviewResult.NO_DIFF);
        verifyNoInteractions(aiClient, workspaceService, eventHooks);
    }

    @Test
    void droppedBlockerDoesNotDriveDecisionOrFindingEvent() {
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(ChatTurn.text(envelope("fabricatedCall();", "BLOCKER") + "\n{\"blocker\": 99}"));
        assertThat(review(true)).isEqualTo(AgentReviewService.ReviewResult.POSTED);
        verify(repositoryClient).postReviewReturningId(eq("owner"), eq("repo"), eq(1L),
                argThat(body -> !body.contains("Broken.")), eq(PostReviewAction.NONE));
        verify(repositoryClient).postReviewAction("owner", "repo", 1L, PostReviewAction.APPROVE);
        verifyNoInteractions(eventHooks);
        verify(audits).recordFindingDisposition(any(), eq(AuditEventType.FINDING_DROPPED), anyString(),
                eq("Example.java"), eq("NEW"), eq(1), eq("fabricatedCall();"), eq("BLOCKER"), isNull(),
                eq("QUOTE"), anyString(), eq(HEAD), eq(BASE));
        verify(state).upsertRevision(argThat(r -> !r.isDegraded() && HEAD.equals(r.getHeadSha())
                && BASE.equals(r.getEffectiveBaseSha()) && "100".equals(r.getLastSeenCommentId())), eq(LEASE));
        var order = inOrder(leases, repositoryClient, workspaceService);
        order.verify(leases).acquire(9L, "owner", "repo", 1L);
        order.verify(repositoryClient).getReviewSnapshot("owner", "repo", 1L);
        order.verify(workspaceService).prepareWorkspaceAtRevision(repositoryClient, "owner", "repo", HEAD, 1L);
        order.verify(leases).release(LEASE);
    }

    @Test
    void kickoffContainsLabeledPriorDigest() {
        ReviewFindingRecord prior = new ReviewFindingRecord();
        prior.setId(UUID.randomUUID());
        prior.setBody("Stored history");
        when(priors.load(repositoryClient, LEASE, AgentReviewWorkflow.KEY, "bot"))
                .thenReturn(new PriorFindingsDigest(List.of(new PriorFindingsDigest.PriorFinding(prior, List.of(), false, null)),
                        false, emptyDigest.commentCursor()));
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(ChatTurn.text("{\"summary\":\"Done\",\"findings\":[]}"));
        assertThat(review(false)).isEqualTo(AgentReviewService.ReviewResult.POSTED);
        verify(aiClient).chatWithTools(anyList(), argThat(message -> message.toString().contains("History to evaluate")
                && message.toString().contains("NOT current code") && message.toString().contains("Stored history")),
                anyList(), anyString(), isNull(), anyInt());
    }

    @Test
    void shaCheckoutFailureStopsReviewAndReleasesLease() {
        when(workspaceService.prepareWorkspaceAtRevision(repositoryClient, "owner", "repo", HEAD, 1L))
                .thenReturn(WorkspaceResult.failure("SHA checkout failed"));
        assertThat(review(false)).isEqualTo(AgentReviewService.ReviewResult.FAILED);
        verifyNoInteractions(aiClient);
        verify(repositoryClient).postPullRequestComment(eq("owner"), eq("repo"), eq(1L),
                argThat(body -> body.contains("setup-failure") && !body.contains("SHA checkout failed")));
        verify(leases).release(LEASE);
        verify(state, never()).upsertRevision(any(), any());
    }

    @Test
    void explicitSkipRunsAfterLeaseAndSnapshotWithoutAgent() {
        when(skipService.shouldSkip(repositoryClient, LEASE, AgentReviewWorkflow.KEY, "bot",
                new ReviewSnapshot(HEAD, BASE, BASE, DIFF, true), true)).thenReturn(true);
        assertThat(service.reviewPullRequest(payload, 1, true, null, new AgentReviewService.SeverityThresholds(0, null, null),
                1L, null, ReviewFence.NONE, true)).isEqualTo(AgentReviewService.ReviewResult.SKIPPED);
        verifyNoInteractions(aiClient, workspaceService, eventHooks);
        verify(repositoryClient).postPullRequestComment("owner", "repo", 1L, "No changes since last review at `aaaaaaa`.");
        verify(repositoryClient).removePullRequestReaction("owner", "repo", 1L, "eyes");
        verify(leases).release(LEASE);
    }

    @Test
    void leaseTimeoutPostsFailureAndClearsBothAcknowledgements() throws Exception {
        payload = AgentJackson.mapper().readValue("{\"repository\":{\"name\":\"repo\",\"owner\":{\"login\":\"owner\"}},"
                + "\"pull_request\":{\"number\":1},\"comment\":{\"id\":77}}", WebhookPayload.class);
        when(leases.acquire(9L, "owner", "repo", 1L)).thenThrow(
                new ReviewRunLeaseService.LeaseAcquisitionTimeoutException(9L, "owner", "repo", 1L, Duration.ofSeconds(1)));
        assertThat(review(false)).isEqualTo(AgentReviewService.ReviewResult.FAILED);
        verify(repositoryClient).postPullRequestComment(eq("owner"), eq("repo"), eq(1L), anyString());
        verify(repositoryClient).removePullRequestReaction("owner", "repo", 1L, "eyes");
        verify(repositoryClient).removeReaction("owner", "repo", 77L, "eyes");
        verify(repositoryClient, never()).getReviewSnapshot(any(), any(), any());
        verify(leases, never()).release(any());
    }

    @Test
    void leaseLostBeforePublicationStopsAllWrites() {
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt())).thenAnswer(inv -> {
            doThrow(new ReviewRunLeaseService.LeaseLostException(LEASE)).when(leases).assertHeld(LEASE);
            return ChatTurn.text(envelope("newCall();", "BLOCKER"));
        });
        assertThat(review(true)).isEqualTo(AgentReviewService.ReviewResult.FAILED);
        verify(repositoryClient, never()).postReviewReturningId(any(), any(), any(), any(), any());
        verify(repositoryClient, never()).postPullRequestComment(any(), any(), any(), any());
        verify(state, never()).upsertRevision(any(), any());
        verify(audits, org.mockito.Mockito.atLeastOnce()).recordGroundingWarning(any(), anyString(), eq("lease lost"), eq(HEAD), eq(BASE));
        verify(leases).release(LEASE);
    }

    @Test
    void unparseableOutputPublishesOnlyFailureNoteAndDegradedRevision() {
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(ChatTurn.text("Fabricated Markdown finding\n{\"blocker\": 8}"));
        assertThat(review(true)).isEqualTo(AgentReviewService.ReviewResult.POSTED);
        verify(repositoryClient).postReviewReturningId(any(), any(), any(),
                argThat(body -> body.contains(FindingGroundingService.PARSE_FAILURE) && !body.contains("Fabricated")),
                eq(PostReviewAction.NONE));
        verify(state).upsertRevision(argThat(ReviewRevisionRecord::isDegraded), eq(LEASE));
        verifyNoInteractions(eventHooks);
    }

    @Test
    void unpinnedReviewUsesDiffOnlyAndSummaryDelivery() {
        when(repositoryClient.getReviewSnapshot("owner", "repo", 1L)).thenReturn(ReviewSnapshot.summaryOnly(DIFF));
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(ChatTurn.text(envelope("newCall();", "BLOCKER")));
        assertThat(review(true)).isEqualTo(AgentReviewService.ReviewResult.POSTED);
        verifyNoInteractions(workspaceService);
        verify(repositoryClient, never()).getFileContent(any(), any(), any(), any());
        verify(repositoryClient).postReviewReturningId(any(), any(), any(),
                argThat(body -> body.contains("Broken.") && body.contains("diff-only")), eq(PostReviewAction.REQUEST_CHANGES));
        verify(state).upsertRevision(argThat(ReviewRevisionRecord::isDegraded), eq(LEASE));
    }

    @Test
    void legacyRequestedFilesUseHeadShaAndPreserveFinalEnvelope() {
        when(aiClient.supportsNativeTools()).thenReturn(false);
        when(aiClient.chatWithTools(anyList(), any(), eq(List.of()), anyString(), isNull(), anyInt()))
                .thenReturn(ChatTurn.text("{\"requestFiles\":[\"Example.java\"]}"),
                        ChatTurn.text("{\"summary\":\"Legacy completed\",\"findings\":[]}"));
        assertThat(review(false)).isEqualTo(AgentReviewService.ReviewResult.POSTED);
        verify(repositoryClient).getFileContent("owner", "repo", "Example.java", HEAD);
        verify(repositoryClient, never()).getFileContent(any(), any(), any(), eq("feature"));
        verify(repositoryClient).postReviewReturningId(any(), any(), any(),
                argThat(body -> body.contains("Legacy completed") && !body.contains(FindingGroundingService.PARSE_FAILURE)),
                eq(PostReviewAction.NONE));
    }

    @Test
    void legacyDuplicateRecoveryCanCompleteWithoutReplayingFullEvidence() {
        when(aiClient.supportsNativeTools()).thenReturn(false);
        List<String> requests = new ArrayList<>();
        List<ChatTurn> turns = List.of(
                ChatTurn.text("{\"requestFiles\":[\"Example.java\"]}"),
                ChatTurn.text("{\"requestFiles\":[\"Example.java\"]}"),
                ChatTurn.text("{\"summary\":\"Recovered\",\"findings\":[]}"));
        when(aiClient.chatWithTools(anyList(), any(), eq(List.of()), anyString(), isNull(), anyInt()))
                .thenAnswer(inv -> {
                    List<org.remus.giteabot.ai.AiMessage> history = inv.getArgument(0);
                    String current = inv.getArgument(1);
                    requests.add(history.stream().map(message -> message.getContent() == null ? "" : message.getContent())
                            .collect(Collectors.joining("\n")) + "\n" + current);
                    return turns.get(requests.size() - 1);
                });

        assertThat(review(false)).isEqualTo(AgentReviewService.ReviewResult.POSTED);
        assertThat(requests).hasSize(3);
        assertThat(requests.get(2).split(java.util.regex.Pattern.quote("newCall();"), -1)).hasSize(2);
        verify(repositoryClient, times(1)).getFileContent("owner", "repo", "Example.java", HEAD);
        verify(repositoryClient, never()).postPullRequestComment(any(), any(), any(), any());
    }

    @Test
    void legacySecondDuplicateFailsWithOneSafeNoticeAndNoReview() {
        when(aiClient.supportsNativeTools()).thenReturn(false);
        when(aiClient.chatWithTools(anyList(), any(), eq(List.of()), anyString(), isNull(), anyInt()))
                .thenReturn(ChatTurn.text("{\"requestFiles\":[\"Example.java\"]}"));

        AgentReviewService.DetailedResult reviewed = service.reviewPullRequestDetailed(payload, 1, true, null,
                new AgentReviewService.SeverityThresholds(0, 0, 0), 1L, null);

        assertThat(reviewed.result()).isEqualTo(AgentReviewService.ReviewResult.FAILED);
        assertThat(reviewed.failure().category()).isEqualTo(ReviewDiagnostics.Category.STALLED_CONTEXT);
        verify(aiClient, times(3)).chatWithTools(anyList(), any(), eq(List.of()), anyString(), isNull(), anyInt());
        assertFailureNoticeOnly();
    }

    @Test
    void mandatoryLegacyContextOverflowFailsBeforeProviderCall() {
        config.getBudget().setMaxHistoryChars(50);
        when(aiClient.supportsNativeTools()).thenReturn(false);

        AgentReviewService.DetailedResult reviewed = service.reviewPullRequestDetailed(payload, 1, false, null,
                new AgentReviewService.SeverityThresholds(null, null, null), 1L, null);

        assertThat(reviewed.result()).isEqualTo(AgentReviewService.ReviewResult.FAILED);
        assertThat(reviewed.failure().category()).isEqualTo(ReviewDiagnostics.Category.MANDATORY_CONTEXT_LIMIT);
        verify(aiClient, never()).chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt());
        assertFailureNoticeOnly();
    }

    @Test
    void completedLegacyReviewWithOversizedRequestedFileIsDegradedAndCannotApprove() {
        config.setMaxFileContentChars(10_000);
        when(aiClient.supportsNativeTools()).thenReturn(false);
        when(repositoryClient.getFileContent("owner", "repo", "Example.java", HEAD))
                .thenReturn("long source line\n".repeat(4_000));
        when(aiClient.chatWithTools(anyList(), any(), eq(List.of()), anyString(), isNull(), anyInt()))
                .thenReturn(ChatTurn.text("{\"requestFiles\":[\"Example.java\"]}"),
                        ChatTurn.text("{\"summary\":\"Completed review\",\"findings\":[],"
                                + "\"classification\":{\"blocker\":0,\"medium\":0,\"low\":0}}"));

        assertThat(review(true)).isEqualTo(AgentReviewService.ReviewResult.POSTED);
        verify(repositoryClient).postReviewReturningId(any(), any(), any(),
                argThat(body -> body.contains("Completed review")
                        && body.contains("Repository evidence acquisition remains incomplete for 1 requested read(s).")),
                eq(PostReviewAction.NONE));
        verify(repositoryClient, never()).postReviewAction(anyString(), anyString(), anyLong(), any());
        verify(state).upsertRevision(argThat(ReviewRevisionRecord::isDegraded), eq(LEASE));
    }

    @Test
    void completedLegacyReviewWithUnavailableRequestedFileIsDegradedWithoutRefFallback() {
        when(aiClient.supportsNativeTools()).thenReturn(false);
        when(repositoryClient.getFileContent("owner", "repo", "Missing.java", HEAD))
                .thenThrow(new IllegalStateException("private source location"));
        when(aiClient.chatWithTools(anyList(), any(), eq(List.of()), anyString(), isNull(), anyInt()))
                .thenReturn(ChatTurn.text("{\"requestFiles\":[\"Missing.java\"]}"),
                        ChatTurn.text("{\"summary\":\"Completed review\",\"findings\":[]}"));

        assertThat(review(true)).isEqualTo(AgentReviewService.ReviewResult.POSTED);
        verify(repositoryClient).postReviewReturningId(any(), any(), any(),
                argThat(body -> body.contains("Completed review")
                        && body.contains("Repository evidence acquisition remains incomplete for 1 requested read(s).")
                        && !body.contains("private source location")), eq(PostReviewAction.NONE));
        verify(repositoryClient, never()).getFileContent(any(), any(), any(), eq("feature"));
        verify(repositoryClient, never()).postReviewAction(anyString(), anyString(), anyLong(), any());
        verify(state).upsertRevision(argThat(ReviewRevisionRecord::isDegraded), eq(LEASE));
        verify(repositoryClient, never()).postPullRequestComment(any(), any(), any(), any());
    }

    @Test
    void leaseLossDuringLegacyFileReadStopsBatchAndPreventsContinuationOrPublication() {
        when(aiClient.supportsNativeTools()).thenReturn(false);
        when(aiClient.chatWithTools(anyList(), any(), eq(List.of()), anyString(), isNull(), anyInt()))
                .thenReturn(ChatTurn.text("{\"requestFiles\":[\"Example.java\",\"Second.java\"]}"));
        when(repositoryClient.getFileContent("owner", "repo", "Example.java", HEAD)).thenAnswer(invocation -> {
            doThrow(new ReviewRunLeaseService.LeaseLostException(LEASE)).when(leases).assertHeld(LEASE);
            return "newCall();";
        });

        assertThat(review(false)).isEqualTo(AgentReviewService.ReviewResult.FAILED);
        verify(repositoryClient, never()).getFileContent("owner", "repo", "Second.java", HEAD);
        verify(aiClient, times(1)).chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt());
        verify(repositoryClient, never()).postReviewReturningId(any(), any(), any(), any(), any());
        verify(repositoryClient, never()).postPullRequestComment(any(), any(), any(), any());
        verify(state, never()).upsertRevision(any(), any());
    }

    @Test
    void doubleCheckDropsVerifiedBlockerBeforeFormalAction() {
        when(doubleCheck.check(anyList(), any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            List<Candidate> candidates = inv.getArgument(0);
            return candidates.stream().collect(Collectors.toMap(c -> c.finding().id(),
                    c -> new DoubleCheckOutcome(Verdict.DROP, "This is the intended fix")));
        });
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(ChatTurn.text(envelope("newCall();", "BLOCKER") + "\n{\"blocker\": 1}"));
        assertThat(review(true)).isEqualTo(AgentReviewService.ReviewResult.POSTED);
        verify(repositoryClient).postReviewReturningId(any(), any(), any(),
                argThat(body -> !body.contains("Broken.")), eq(PostReviewAction.NONE));
        verify(repositoryClient).postReviewAction("owner", "repo", 1L, PostReviewAction.APPROVE);
        verifyNoInteractions(eventHooks);
    }

    @Test
    void incompletePriorCommentScanDoesNotApproveAgenticReview() {
        when(priors.load(repositoryClient, LEASE, AgentReviewWorkflow.KEY, "bot"))
                .thenReturn(new PriorFindingsDigest(List.of(), false, emptyDigest.commentCursor()));
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(ChatTurn.text("{\"summary\":\"Reviewed\",\"findings\":[]}"));

        assertThat(review(true)).isEqualTo(AgentReviewService.ReviewResult.POSTED);

        verify(repositoryClient).postReviewReturningId(any(), any(), any(),
                anyString(), eq(PostReviewAction.NONE));
        verify(repositoryClient, never()).postReviewAction(anyString(), anyString(), anyLong(), any());
        verify(repositoryClient, never()).postPullRequestComment(any(), any(), any(), any());
        verify(state).upsertRevision(argThat(ReviewRevisionRecord::isDegraded), eq(LEASE));
    }

    @Test
    void failedThreadActionDoesNotApproveAgenticReview() {
        ReviewFindingRecord prior = new ReviewFindingRecord();
        prior.setId(UUID.randomUUID());
        when(threadActions.execute(eq(repositoryClient), eq(LEASE), anyList(), eq("bot"), any(),
                eq(HEAD), eq(BASE), any()))
                .thenReturn(List.of(new ThreadAction.Withdraw(
                        new PriorFindingsDigest.PriorFinding(prior, List.of(), false, null), "withdrawal failed")));
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(ChatTurn.text("{\"summary\":\"Reviewed\",\"findings\":[]}"));

        assertThat(review(true)).isEqualTo(AgentReviewService.ReviewResult.POSTED);

        verify(repositoryClient).postReviewReturningId(any(), any(), any(),
                anyString(), eq(PostReviewAction.NONE));
        verify(repositoryClient, never()).postReviewAction(anyString(), anyString(), anyLong(), any());
        verify(repositoryClient, never()).postPullRequestComment(any(), any(), any(), any());
        verify(state).upsertRevision(argThat(ReviewRevisionRecord::isDegraded), eq(LEASE));
    }

    @Test
    void failedDoubleCheckHoldsMediumAndDoesNotApproveDegradedReview() {
        when(doubleCheck.check(anyList(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("provider unavailable"));
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(ChatTurn.text(envelope("newCall();", "MEDIUM")));
        assertThat(review(true)).isEqualTo(AgentReviewService.ReviewResult.POSTED);
        verify(repositoryClient).postReviewReturningId(any(), any(), any(),
                argThat(body -> body.contains("Double-check unavailable") && !body.contains("Broken.")), eq(PostReviewAction.NONE));
        verify(repositoryClient, never()).postReviewAction(anyString(), anyString(), anyLong(), any());
        verify(state).upsertRevision(argThat(ReviewRevisionRecord::isDegraded), eq(LEASE));
        verifyNoInteractions(eventHooks);
    }

    @Test
    void pinnedReadFailureDropsFindingWithoutFallingBackToBranch() {
        when(repositoryClient.getFileContent("owner", "repo", "Example.java", HEAD))
                .thenThrow(new IllegalStateException("revision unavailable"));
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(ChatTurn.text(envelope("newCall();", "BLOCKER")));
        assertThat(review(true)).isEqualTo(AgentReviewService.ReviewResult.POSTED);
        verify(repositoryClient).postReviewReturningId(any(), any(), any(),
                argThat(body -> !body.contains("Broken.")), eq(PostReviewAction.NONE));
        verify(repositoryClient, never()).getFileContent(any(), any(), any(), eq("feature"));
        verify(state).upsertRevision(argThat(ReviewRevisionRecord::isDegraded), eq(LEASE));
        verify(leases).release(LEASE);
    }

    @Test
    void failedPriorThreadActionMarksRevisionDegradedForRetry() {
        var prior = new PriorFindingsDigest.PriorFinding(new ReviewFindingRecord(), List.of(), true, "reply");
        when(threadActions.execute(any(), any(), anyList(), anyString(), any(), anyString(), anyString(), any()))
                .thenReturn(List.of(new ThreadAction.Withdraw(prior, "retry")));
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(ChatTurn.text("{\"summary\":\"Done\",\"findings\":[]}"));
        assertThat(review(false)).isEqualTo(AgentReviewService.ReviewResult.POSTED);
        verify(state).upsertRevision(argThat(ReviewRevisionRecord::isDegraded), eq(LEASE));
    }

    @Test
    void leaseReleaseFailureDoesNotReplaceSuccessfulReview() {
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(ChatTurn.text(envelope("fabricatedCall();", "BLOCKER")));
        doThrow(new IllegalStateException("database unavailable")).when(leases).release(LEASE);
        assertThat(review(true)).isEqualTo(AgentReviewService.ReviewResult.POSTED);
        verify(state).upsertRevision(any(), eq(LEASE));
        verify(leases).release(LEASE);
    }

    private AgentReviewService.ReviewResult review(boolean formal) {
        return service.reviewPullRequest(payload, 2, formal, null,
                new AgentReviewService.SeverityThresholds(0, null, null), 1L, null);
    }

    private String envelope(String quote, String severity) {
        return "{\"summary\":\"Reviewed\",\"findings\":[{\"path\":\"Example.java\",\"side\":\"new\",\"line\":1,"
                + "\"severity\":\"" + severity + "\",\"quote\":\"" + quote + "\",\"body\":\"Broken.\"}]}";
    }

    private ChatTurn exploration(int round) {
        return new ChatTurn("Let me check the remaining files...",
                List.of(new ToolCall("read-diff-" + round, "pr-diff",
                        AgentJackson.mapper().createObjectNode().put("path", "Example.java"))),
                StopReason.TOOL_USE, 100, 20);
    }
}
