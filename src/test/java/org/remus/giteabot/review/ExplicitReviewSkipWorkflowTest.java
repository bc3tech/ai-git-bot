package org.remus.giteabot.review;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.GiteaClientFactory;
import org.remus.giteabot.config.ReviewChunkingProperties;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.prworkflow.PrWorkflow;
import org.remus.giteabot.prworkflow.PrWorkflowContext;
import org.remus.giteabot.prworkflow.WorkflowResultStatus;
import org.remus.giteabot.prworkflow.agentreview.AgentReviewService;
import org.remus.giteabot.prworkflow.agentreview.AgentReviewServiceFactory;
import org.remus.giteabot.prworkflow.agentreview.AgentReviewWorkflow;
import org.remus.giteabot.prworkflow.config.WorkflowSelectionService;
import org.remus.giteabot.prworkflow.review.CodeReviewServiceFactory;
import org.remus.giteabot.prworkflow.review.ReviewWorkflow;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.model.CommentScan;
import org.remus.giteabot.repository.model.ReviewSnapshot;
import org.remus.giteabot.review.state.ReviewRevisionRecord;
import org.remus.giteabot.review.state.ReviewRunLeaseService;
import org.remus.giteabot.review.state.ReviewStateService;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExplicitReviewSkipWorkflowTest {
    private final RepositoryApiClient api = mock(RepositoryApiClient.class);
    private final ReviewStateService state = mock(ReviewStateService.class);
    private final ReviewRunLeaseService leases = mock(ReviewRunLeaseService.class);
    private final GiteaClientFactory clients = mock(GiteaClientFactory.class);
    private final CodeReviewServiceFactory standardFactory = mock(CodeReviewServiceFactory.class);
    private final AgentReviewServiceFactory agentFactory = mock(AgentReviewServiceFactory.class);
    private final CodeReviewService standard = mock(CodeReviewService.class);
    private final AgentReviewService agent = mock(AgentReviewService.class);
    private PrWorkflowContext context;
    private ReviewSkipService skip;

    @BeforeEach
    void setUp() {
        skip = new ReviewSkipService(state, leases, clients);
        Bot bot = new Bot(); bot.setId(1L); bot.setUsername("bot");
        WebhookPayload payload = new WebhookPayload();
        WebhookPayload.Repository repo = new WebhookPayload.Repository();
        WebhookPayload.Owner owner = new WebhookPayload.Owner(); owner.setLogin("owner");
        repo.setOwner(owner); repo.setName("repo"); payload.setRepository(repo);
        WebhookPayload.PullRequest pr = new WebhookPayload.PullRequest(); pr.setNumber(7L); payload.setPullRequest(pr);
        context = new PrWorkflowContext(bot, payload, 1L, (name, log) -> {}, () -> false);
        when(clients.getApiClient(any())).thenReturn(api);
    }

    private PrWorkflow workflow(String key) {
        WorkflowSelectionService selection = mock(WorkflowSelectionService.class);
        if (key.equals("review")) {
            when(standardFactory.create(any(), any(), anyInt(), anyInt(), anyInt(), any(), any())).thenReturn(standard);
            when(standard.runReview(any(), any(), any())).thenReturn(new CodeReviewService.ReviewRun(true, true));
            return new ReviewWorkflow(standardFactory, clients, selection, new ReviewChunkingProperties(), skip);
        }
        when(agentFactory.create(any())).thenReturn(agent);
        when(agent.reviewPullRequest(any(), anyInt(), anyBoolean(), anyString(), any(), any(), any(), any()))
                .thenReturn(AgentReviewService.ReviewResult.POSTED);
        return new AgentReviewWorkflow(agentFactory, selection, skip);
    }

    @ParameterizedTest @ValueSource(strings = {"review", "agentic-review"})
    void reviewCommandWithCleanUnchangedRevisionPostsReplyWithoutReview(String key) {
        WebhookPayload.Comment comment = new WebhookPayload.Comment(); comment.setId(12L); comment.setBody("@bot /review");
        context.payload().setComment(comment);
        context = new PrWorkflowContext(context.bot(), context.payload(), 1L, (name, log) -> {}, () -> false,
                Map.of(PrWorkflowContext.HINT_EXPLICIT_REVIEW_REQUEST, "true"));
        ReviewRevisionRecord revision = new ReviewRevisionRecord();
        revision.setHeadSha("abcdef012345"); revision.setEffectiveBaseSha("base"); revision.setReviewedAt(Instant.now());
        when(state.findRevision(1L, "owner", "repo", 7L, key)).thenReturn(Optional.of(revision));
        when(leases.acquire(1L, "owner", "repo", 7L))
                .thenReturn(new ReviewRunLeaseService.LeaseToken(1L, "owner", "repo", 7L, "worker", 1));
        when(api.getReviewSnapshot("owner", "repo", 7L))
                .thenReturn(new ReviewSnapshot("abcdef012345", "target", "base", "diff", true));
        when(api.listReviewThreadComments("owner", "repo", 7L)).thenReturn(new CommentScan<>(List.of(), true));
        when(api.listPullRequestComments("owner", "repo", 7L)).thenReturn(new CommentScan<>(List.of(), true));
        assertThat(workflow(key).run(context).status()).isEqualTo(WorkflowResultStatus.SKIPPED);
        verifyNoInteractions(standard, agent);
        verify(api).postPullRequestComment("owner", "repo", 7L, "No changes since last review at `abcdef0`.");
        verify(api).removeReaction("owner", "repo", 12L, "eyes");
    }

    @ParameterizedTest @ValueSource(strings = {"review", "agentic-review"})
    void synchronizeTakesNormalReviewPathEvenWithUnchangedRevision(String key) {
        context.payload().setAction("synchronize");
        assertThat(workflow(key).run(context).status()).isEqualTo(WorkflowResultStatus.SUCCESS);
        verifyNoInteractions(state, leases);
        verify(api, never()).postPullRequestComment(any(), any(), any(), any());
        if (key.equals("review")) verify(standard).runReview(any(), any(), any());
        else verify(agent).reviewPullRequest(any(), anyInt(), anyBoolean(), anyString(), any(), any(), any(), any());
    }
}
