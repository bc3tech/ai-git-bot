package org.remus.giteabot.prworkflow.agentreview;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.remus.giteabot.admin.Bot;
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
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.eventhook.EventHookPublisher;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.prworkflow.PrWorkflowContext;
import org.remus.giteabot.prworkflow.WorkflowResult;
import org.remus.giteabot.prworkflow.WorkflowResultStatus;
import org.remus.giteabot.prworkflow.config.WorkflowSelectionService;
import org.remus.giteabot.repository.PostReviewAction;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.audit.PrAuditEventService;
import org.remus.giteabot.review.ReviewSkipService;
import org.remus.giteabot.review.grounding.FindingGroundingService;
import org.remus.giteabot.review.grounding.PriorFindingsDigest;
import org.remus.giteabot.review.grounding.PriorFindingsService;
import org.remus.giteabot.review.grounding.ThreadActionExecutor;
import org.remus.giteabot.review.state.ReviewRunLeaseService;
import org.remus.giteabot.review.state.ReviewRunLeaseService.LeaseToken;
import org.remus.giteabot.review.state.ReviewStateService;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentReviewDiagnosticsIntegrationTest {

    private static final String DIFF = """
            diff --git a/Example.java b/Example.java
            --- a/Example.java
            +++ b/Example.java
            @@ -1 +1 @@
            -old
            +new
            """;
    private static final String CONTEXT_REQUEST =
            "{\"requestFiles\":[\"private-token.java\"]}";
    private static final String HEAD_SHA = "abcdef012345abcdef012345abcdef012345abcd";
    private static final String BASE_SHA = "b".repeat(40);

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void legacyLimitFailureSurvivesLoopServiceAndWorkflowWithoutPublishingPartialText(boolean repeated) throws Exception {
        AiClient aiClient = mock(AiClient.class);
        RepositoryApiClient repositoryClient = mock(RepositoryApiClient.class);
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        EventHookPublisher eventHooks = mock(EventHookPublisher.class);
        ReviewRunLeaseService leases = mock(ReviewRunLeaseService.class);
        ReviewStateService state = mock(ReviewStateService.class);
        PriorFindingsService priors = mock(PriorFindingsService.class);
        ReviewSkipService skipService = mock(ReviewSkipService.class);
        PrAuditEventService audits = mock(PrAuditEventService.class);
        FindingGroundingService grounding = mock(FindingGroundingService.class);
        ThreadActionExecutor threadActions = mock(ThreadActionExecutor.class);
        LeaseToken lease = new LeaseToken(9L, "owner", "repo", 1L, "diagnostics-test", 1);
        PriorFindingsDigest digest = new PriorFindingsDigest(List.of(), true, null);
        org.remus.giteabot.repository.model.ReviewSnapshot snapshot =
                new org.remus.giteabot.repository.model.ReviewSnapshot(HEAD_SHA, BASE_SHA, BASE_SHA, DIFF, true);
        when(leases.acquire(9L, "owner", "repo", 1L)).thenReturn(lease);
        when(priors.load(repositoryClient, lease, AgentReviewWorkflow.KEY, "bot")).thenReturn(digest);
        when(skipService.shouldSkip(repositoryClient, lease, AgentReviewWorkflow.KEY, "bot", snapshot, false))
                .thenReturn(false);
        AgentConfigProperties config = new AgentConfigProperties();
        config.getBudget().setMaxRounds(20);
        config.getBudget().setMaxContextRounds(10);
        config.getBudget().setMaxTokensPerCall(4096);
        ToolCatalog catalog = new ToolCatalog(config);
        Path workspace = Path.of("agent-review-diagnostics-workspace");
        when(aiClient.supportsNativeTools()).thenReturn(false);
        AtomicInteger calls = new AtomicInteger();
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenAnswer(invocation -> {
                    int turn = calls.incrementAndGet();
                    String request = repeated ? CONTEXT_REQUEST
                            : "{\"requestFiles\":[\"private-token-" + turn + ".java\"]}";
                    return new ChatTurn(request, List.of(), StopReason.END_TURN, 100, 15);
                });
        when(repositoryClient.getReviewSnapshot("owner", "repo", 1L)).thenReturn(snapshot);
        when(repositoryClient.getFileContent(eq("owner"), eq("repo"), anyString(), eq(HEAD_SHA)))
                .thenAnswer(invocation -> "PRIVATE_SOURCE_CONTENT " + invocation.getArgument(2));
        when(workspaceService.prepareWorkspaceAtRevision(repositoryClient, "owner", "repo", HEAD_SHA, 1L))
                .thenReturn(WorkspaceResult.success(workspace));

        Bot bot = new Bot();
        bot.setId(9L);
        AgentReviewService service = new AgentReviewService(
                new AgentReviewContext(repositoryClient, aiClient, "Review the change.", "bot",
                        null, null, Set.of(), 200_000),
                new AgentSessionService(mock(AgentSessionRepository.class)),
                new ToolExecutionService(config, catalog, workspaceService), catalog, workspaceService,
                config, null, bot, eventHooks, leases, state, priors, grounding, threadActions, skipService, audits,
                java.time.Duration.ofMinutes(10),
                org.remus.giteabot.review.grounding.DoubleCheckSettings.defaults(),
                new org.remus.giteabot.review.grounding.DoubleCheckService.Budget(30_000, 8));
        AgentReviewServiceFactory serviceFactory = mock(AgentReviewServiceFactory.class);
        when(serviceFactory.create(eq(bot), any())).thenReturn(service);
        AgentReviewWorkflow workflow = new AgentReviewWorkflow(
                serviceFactory, mock(WorkflowSelectionService.class));
        AtomicReference<String> excerpt = new AtomicReference<>();
        PrWorkflowContext context = new PrWorkflowContext(bot, payload(), 42L,
                (name, text) -> excerpt.set(text), () -> false);

        WorkflowResult result = workflow.run(context);

        assertThat(result.status()).isEqualTo(WorkflowResultStatus.FAILED);
        if (repeated) {
            assertThat(result.summary()).contains("stalled-context");
            assertThat(excerpt.get()).contains("turns=3", "contextRounds=3/10",
                    "executedRequestOccurrences=1", "suppressedRequestOccurrences=2",
                    "repeatedRequestOccurrences=2", "repeatedRequestRounds=2", "noticeStatus=DELIVERED");
        } else {
            assertThat(result.summary()).contains("context-round-limit", "10/10 context rounds", "11 total turns");
            assertThat(excerpt.get()).contains("turns=11", "contextRounds=10/10",
                    "executedRequestOccurrences=10", "suppressedRequestOccurrences=0",
                    "repeatedRequestOccurrences=0", "noticeStatus=DELIVERED");
        }
        assertThat(excerpt.get()).doesNotContain("private-token.java", "PRIVATE_SOURCE_CONTENT");
        verify(aiClient, times(repeated ? 3 : 11))
                .chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt());
        verify(repositoryClient, times(1)).postPullRequestComment(eq("owner"), eq("repo"), eq(1L),
                anyString());
        verify(repositoryClient, never()).postReview(anyString(), anyString(), anyLong(), anyString(), any());
        verify(repositoryClient, never()).postReviewComment(anyString(), anyString(), anyLong(), anyString());
        verify(repositoryClient, never()).postReviewReturningId(any(), any(), any(), any(), any());
        verify(leases, org.mockito.Mockito.atLeastOnce()).assertHeld(lease);
        verify(leases).release(lease);
        verify(workspaceService).prepareWorkspaceAtRevision(repositoryClient, "owner", "repo", HEAD_SHA, 1L);
        verify(repositoryClient, times(repeated ? 1 : 10))
                .getFileContent(eq("owner"), eq("repo"), anyString(), eq(HEAD_SHA));
        org.mockito.Mockito.verifyNoInteractions(state, grounding, threadActions, eventHooks);
        verify(workspaceService).cleanupWorkspace(workspace);
    }

    private static WebhookPayload payload() throws Exception {
        return AgentJackson.mapper().readValue("""
                {"repository":{"name":"repo","owner":{"login":"owner"}},
                 "pull_request":{"number":1,"title":"Update example","head":{"ref":"feature"}}}
                """, WebhookPayload.class);
    }
}
