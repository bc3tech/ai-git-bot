package org.remus.giteabot.prworkflow.agentreview;

import org.junit.jupiter.api.Test;
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

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

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

    @Test
    void legacyContextCapFailureSurvivesLoopServiceAndWorkflowWithoutPublishingPartialText() throws Exception {
        AiClient aiClient = mock(AiClient.class);
        RepositoryApiClient repositoryClient = mock(RepositoryApiClient.class);
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        EventHookPublisher eventHooks = mock(EventHookPublisher.class);
        AgentConfigProperties config = new AgentConfigProperties();
        config.getBudget().setMaxRounds(20);
        config.getBudget().setMaxContextRounds(10);
        config.getBudget().setMaxTokensPerCall(4096);
        ToolCatalog catalog = new ToolCatalog(config);
        Path workspace = Path.of("agent-review-diagnostics-workspace");
        when(aiClient.supportsNativeTools()).thenReturn(false);
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenAnswer(invocation -> new ChatTurn(CONTEXT_REQUEST, List.of(),
                        StopReason.END_TURN, 100, 15));
        when(repositoryClient.getPullRequestDiff("owner", "repo", 1L)).thenReturn(DIFF);
        when(repositoryClient.getFileContent("owner", "repo", "private-token.java", "feature"))
                .thenReturn("PRIVATE_SOURCE_CONTENT");
        when(workspaceService.prepareWorkspace(repositoryClient, "owner", "repo", "feature", 1L))
                .thenReturn(WorkspaceResult.success(workspace));

        Bot bot = new Bot();
        AgentReviewService service = new AgentReviewService(
                new AgentReviewContext(repositoryClient, aiClient, "Review the change.", "bot",
                        null, null, Set.of(), 200_000),
                new AgentSessionService(mock(AgentSessionRepository.class)),
                new ToolExecutionService(config, catalog, workspaceService), catalog, workspaceService,
                config, null, bot, eventHooks);
        AgentReviewServiceFactory serviceFactory = mock(AgentReviewServiceFactory.class);
        when(serviceFactory.create(bot)).thenReturn(service);
        AgentReviewWorkflow workflow = new AgentReviewWorkflow(
                serviceFactory, mock(WorkflowSelectionService.class));
        AtomicReference<String> excerpt = new AtomicReference<>();
        PrWorkflowContext context = new PrWorkflowContext(bot, payload(), 42L,
                (name, text) -> excerpt.set(text), () -> false);

        WorkflowResult result = workflow.run(context);

        assertThat(result.status()).isEqualTo(WorkflowResultStatus.FAILED);
        assertThat(result.summary()).contains("context-round-limit", "10/10 context rounds", "11 total turns");
        assertThat(excerpt.get()).contains("turns=11", "contextRounds=10/10",
                "repeatedRequestOccurrences=10", "repeatedRequestRounds=10", "noticeStatus=DELIVERED");
        assertThat(excerpt.get()).doesNotContain("private-token.java", "PRIVATE_SOURCE_CONTENT");
        verify(aiClient, times(11)).chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt());
        verify(repositoryClient, times(1)).postPullRequestComment(eq("owner"), eq("repo"), eq(1L),
                anyString());
        verify(repositoryClient, never()).postReview(anyString(), anyString(), anyLong(), anyString(), any());
        verify(repositoryClient, never()).postReviewComment(anyString(), anyString(), anyLong(), anyString());
        verify(workspaceService).cleanupWorkspace(workspace);
    }

    private static WebhookPayload payload() throws Exception {
        return AgentJackson.mapper().readValue("""
                {"repository":{"name":"repo","owner":{"login":"owner"}},
                 "pull_request":{"number":1,"title":"Update example","head":{"ref":"feature"}}}
                """, WebhookPayload.class);
    }
}
