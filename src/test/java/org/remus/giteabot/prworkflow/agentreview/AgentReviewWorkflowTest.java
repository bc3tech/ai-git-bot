package org.remus.giteabot.prworkflow.agentreview;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.prworkflow.PrWorkflowCategory;
import org.remus.giteabot.prworkflow.PrWorkflowContext;
import org.remus.giteabot.prworkflow.WorkflowResult;
import org.remus.giteabot.prworkflow.WorkflowResultStatus;
import org.remus.giteabot.prworkflow.config.WorkflowSelectionService;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AgentReviewWorkflowTest {

    @Mock
    private AgentReviewServiceFactory serviceFactory;

    @Mock
    private WorkflowSelectionService selectionService;

    private AgentReviewWorkflow workflow() {
        return new AgentReviewWorkflow(serviceFactory, selectionService);
    }

    private PrWorkflowContext context(Bot bot) {
        return new PrWorkflowContext(bot, new WebhookPayload(), 1L,
                (name, log) -> { }, () -> false);
    }

    @Test
    void metadata_isStableAndReview() {
        AgentReviewWorkflow wf = workflow();
        assertEquals("agentic-review", wf.key());
        assertEquals(PrWorkflowCategory.REVIEW, wf.category());
        assertEquals(6, wf.paramsSchema().fields().size());
        assertFalse(wf.paramsSchema().isEmpty());
    }

    @Test
    void run_usesDefaults_whenNoConfiguration_andReportsSuccess() {
        AgentReviewService service = mock(AgentReviewService.class);
        when(serviceFactory.create(any())).thenReturn(service);
        when(service.reviewPullRequestDetailed(any(), anyInt(), anyBoolean(), anyString(),
                any(AgentReviewService.SeverityThresholds.class), anyLong(), any()))
                .thenReturn(result(AgentReviewService.ReviewResult.POSTED));

        Bot bot = new Bot();

        WorkflowResult result = workflow().run(context(bot));

        assertEquals(WorkflowResultStatus.SUCCESS, result.status());
        verify(service).reviewPullRequestDetailed(any(), eq(12), eq(false),
                eq(AgentReviewWorkflow.DEFAULT_FORMAL_REVIEW_DECISION_PROMPT),
                eq(new AgentReviewService.SeverityThresholds(null, null, null)), eq(1L), isNull());
    }

    @Test
    void run_honoursConfiguredParams_andSkipWhenNoReview() {
        Bot bot = new Bot();
        org.remus.giteabot.prworkflow.config.WorkflowConfiguration cfg =
                new org.remus.giteabot.prworkflow.config.WorkflowConfiguration();
        cfg.setId(7L);
        bot.setWorkflowConfiguration(cfg);

        when(selectionService.resolveParams(7L, "agentic-review")).thenReturn(Map.of("maxToolRounds", 5));
        AgentReviewService service = mock(AgentReviewService.class);
        when(serviceFactory.create(any())).thenReturn(service);
        lenient().when(service.reviewPullRequestDetailed(any(), anyInt(), anyBoolean(), anyString(),
                        any(AgentReviewService.SeverityThresholds.class), anyLong(), any()))
                .thenReturn(result(AgentReviewService.ReviewResult.NO_DIFF));

        WorkflowResult result = workflow().run(context(bot));

        assertEquals(WorkflowResultStatus.SKIPPED, result.status());
        verify(service).reviewPullRequestDetailed(any(), eq(5), eq(false),
                eq(AgentReviewWorkflow.DEFAULT_FORMAL_REVIEW_DECISION_PROMPT),
                eq(new AgentReviewService.SeverityThresholds(null, null, null)), eq(1L), isNull());
    }

    @Test
    void run_reportsFailure_whenReviewCannotComplete() {
        AgentReviewService service = mock(AgentReviewService.class);
        when(serviceFactory.create(any())).thenReturn(service);
        when(service.reviewPullRequestDetailed(any(), anyInt(), anyBoolean(), anyString(),
                any(AgentReviewService.SeverityThresholds.class), anyLong(), any()))
                .thenReturn(result(AgentReviewService.ReviewResult.FAILED,
                        ReviewDiagnostics.Category.CONTEXT_ROUND_LIMIT));

        WorkflowResult result = workflow().run(context(new Bot()));

        assertEquals(WorkflowResultStatus.FAILED, result.status());
    }

    @ParameterizedTest
    @CsvSource({"POSTED,SUCCESS", "NO_DIFF,SKIPPED", "FAILED,FAILED"})
    void clarification_mapsServiceResult(AgentReviewService.ReviewResult serviceResult,
                                         WorkflowResultStatus expected) {
        AgentReviewService service = mock(AgentReviewService.class);
        when(serviceFactory.create(any())).thenReturn(service);
        when(service.answerClarificationDetailed(any(), anyString(), anyInt(), anyLong()))
                .thenReturn(result(serviceResult));
        PrWorkflowContext context = new PrWorkflowContext(new Bot(), new WebhookPayload(), 1L,
                (name, log) -> { }, () -> false,
                Map.of(PrWorkflowContext.HINT_AGENTIC_REVIEW_CLARIFICATION, "Why was this changed?"), null);

        assertEquals(expected, workflow().run(context).status());
    }

    @Test
    void run_enablesFormalDecision_whenParamsSet() {
        Bot bot = new Bot();
        org.remus.giteabot.prworkflow.config.WorkflowConfiguration cfg =
                new org.remus.giteabot.prworkflow.config.WorkflowConfiguration();
        cfg.setId(7L);
        bot.setWorkflowConfiguration(cfg);

        when(selectionService.resolveParams(7L, "agentic-review")).thenReturn(Map.of(
                "maxToolRounds", 8,
                "enableFormalReviewDecision", true,
                "formalReviewDecisionPrompt", "Custom criteria here",
                "blockerThreshold", 0,
                "mediumThreshold", 2,
                "lowThreshold", 5));

        AgentReviewService service = mock(AgentReviewService.class);
        when(serviceFactory.create(any())).thenReturn(service);
        when(service.reviewPullRequestDetailed(any(), anyInt(), anyBoolean(), anyString(),
                any(AgentReviewService.SeverityThresholds.class), anyLong(), any()))
                .thenReturn(result(AgentReviewService.ReviewResult.POSTED));

        WorkflowResult result = workflow().run(context(bot));

        assertEquals(WorkflowResultStatus.SUCCESS, result.status());
        verify(service).reviewPullRequestDetailed(any(), eq(8), eq(true),
                eq("Custom criteria here"),
                eq(new AgentReviewService.SeverityThresholds(0, 2, 5)), eq(1L), isNull());
    }

    @Test
    void boolParam_defaultsToFalse_whenMissing() {
        when(selectionService.resolveParams(7L, "agentic-review")).thenReturn(Map.of());

        Bot bot = new Bot();
        org.remus.giteabot.prworkflow.config.WorkflowConfiguration cfg =
                new org.remus.giteabot.prworkflow.config.WorkflowConfiguration();
        cfg.setId(7L);
        bot.setWorkflowConfiguration(cfg);

        AgentReviewService service = mock(AgentReviewService.class);
        when(serviceFactory.create(any())).thenReturn(service);
        when(service.reviewPullRequestDetailed(any(), anyInt(), anyBoolean(), anyString(),
                any(AgentReviewService.SeverityThresholds.class), anyLong(), any()))
                .thenReturn(result(AgentReviewService.ReviewResult.POSTED));

        workflow().run(context(bot));

        verify(service).reviewPullRequestDetailed(any(), anyInt(), eq(false), anyString(),
                eq(new AgentReviewService.SeverityThresholds(null, null, null)), eq(1L), isNull());
    }

    @Test
    void run_cancelledBeforeServiceCreationDoesNotAttemptNotice() {
        PrWorkflowContext context = new PrWorkflowContext(new Bot(), new WebhookPayload(), 1L,
                (name, excerpt) -> { }, () -> true);

        org.junit.jupiter.api.Assertions.assertThrows(
                org.remus.giteabot.prworkflow.WorkflowCancelledException.class,
                () -> workflow().run(context));

        verifyNoInteractions(serviceFactory);
    }

    @Test
    void factoryFailureUsesPreServiceReporterAndPersistsItsUnavailableState() {
        Bot bot = new Bot();
        when(serviceFactory.create(bot)).thenThrow(new IllegalStateException("private failure"));
        AgentReviewService.DetailedResult setupFailure = new AgentReviewService.DetailedResult(
                AgentReviewService.ReviewResult.FAILED,
                new ReviewDiagnostics.Collector(null, null, null, 1L,
                        null, null, null, null, null).snapshot(ReviewDiagnostics.Category.SETUP_FAILURE),
                ReviewDiagnostics.NoticeStatus.UNAVAILABLE, "repository-client-unavailable");
        when(serviceFactory.reportSetupFailure(eq(bot), any(), eq(1L), eq(false))).thenReturn(setupFailure);
        AtomicReference<String> excerpt = new AtomicReference<>();
        PrWorkflowContext context = new PrWorkflowContext(bot, new WebhookPayload(), 1L,
                (name, text) -> excerpt.set(text), () -> false);

        WorkflowResult result = workflow().run(context);

        assertEquals(WorkflowResultStatus.FAILED, result.status());
        org.junit.jupiter.api.Assertions.assertTrue(result.summary().contains("setup-failure"));
        org.junit.jupiter.api.Assertions.assertTrue(excerpt.get().contains("noticeStatus=UNAVAILABLE"));
        org.junit.jupiter.api.Assertions.assertTrue(excerpt.get().contains("repository-client-unavailable"));
        verify(serviceFactory).reportSetupFailure(eq(bot), any(), eq(1L), eq(false));
    }

    @Test
    void stepPersistenceFailureDoesNotTriggerASecondNoticeAttempt() {
        Bot bot = new Bot();
        AgentReviewService service = mock(AgentReviewService.class);
        when(serviceFactory.create(any())).thenReturn(service);
        when(service.reviewPullRequestDetailed(any(), anyInt(), anyBoolean(), anyString(),
                any(AgentReviewService.SeverityThresholds.class), anyLong(), any()))
                .thenReturn(result(AgentReviewService.ReviewResult.FAILED,
                        ReviewDiagnostics.Category.CONTEXT_ROUND_LIMIT));
        PrWorkflowContext context = new PrWorkflowContext(bot, new WebhookPayload(), 1L,
                (name, excerpt) -> { throw new IllegalStateException("persistence failure"); }, () -> false);

        WorkflowResult result = workflow().run(context);

        assertEquals(WorkflowResultStatus.FAILED, result.status());
        assertTrue(result.summary().contains("context-round-limit"));
        verify(serviceFactory, never()).reportSetupFailure(any(), any(), anyLong(), anyBoolean());
    }

    @Test
    void clarificationPassesWorkflowRunIdToDetailedService() {
        Bot bot = new Bot();
        AgentReviewService service = mock(AgentReviewService.class);
        when(serviceFactory.create(any())).thenReturn(service);
        when(service.answerClarificationDetailed(any(), anyString(), anyInt(), anyLong()))
                .thenReturn(result(AgentReviewService.ReviewResult.POSTED));
        PrWorkflowContext context = new PrWorkflowContext(bot, new WebhookPayload(), 55L,
                (name, excerpt) -> { }, () -> false,
                Map.of(PrWorkflowContext.HINT_AGENTIC_REVIEW_CLARIFICATION, "Question"), null);

        workflow().run(context);

        verify(service).answerClarificationDetailed(any(), eq("Question"), anyInt(), eq(55L));
    }

    private static AgentReviewService.DetailedResult result(AgentReviewService.ReviewResult result) {
        return new AgentReviewService.DetailedResult(result, null,
                ReviewDiagnostics.NoticeStatus.NOT_ATTEMPTED, null);
    }

    private static AgentReviewService.DetailedResult result(
            AgentReviewService.ReviewResult result, ReviewDiagnostics.Category category) {
        var diagnostics = new ReviewDiagnostics.Collector(10, 20, 4096, 1L,
                "owner", "repo", 1L, "test", 1L).snapshot(category);
        return new AgentReviewService.DetailedResult(result, diagnostics,
                ReviewDiagnostics.NoticeStatus.DELIVERED, null);
    }
}
