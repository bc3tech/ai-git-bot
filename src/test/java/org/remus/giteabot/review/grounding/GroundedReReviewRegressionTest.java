package org.remus.giteabot.review.grounding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.remus.giteabot.admin.AiClientFactory;
import org.remus.giteabot.admin.AiIntegrationService;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.GiteaClientFactory;
import org.remus.giteabot.agent.session.AgentSessionRepository;
import org.remus.giteabot.agent.session.AgentSessionService;
import org.remus.giteabot.agent.tools.ToolCatalog;
import org.remus.giteabot.agent.validation.ToolExecutionService;
import org.remus.giteabot.agent.validation.WorkspaceResult;
import org.remus.giteabot.agent.validation.WorkspaceService;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.ai.AiMessage;
import org.remus.giteabot.ai.ChatTurn;
import org.remus.giteabot.ai.StopReason;
import org.remus.giteabot.audit.AuditEventType;
import org.remus.giteabot.audit.PrAuditEvent;
import org.remus.giteabot.audit.PrAuditEventRepository;
import org.remus.giteabot.audit.PrAuditEventService;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.config.ReviewConfigProperties;
import org.remus.giteabot.eventhook.EventHookPublisher;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.prworkflow.agentreview.AgentReviewContext;
import org.remus.giteabot.prworkflow.agentreview.AgentReviewService;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.model.CommentScan;
import org.remus.giteabot.repository.model.ReviewAnchorComment;
import org.remus.giteabot.repository.model.ReviewComment;
import org.remus.giteabot.repository.model.ReviewPublicationResult;
import org.remus.giteabot.repository.model.ReviewSnapshot;
import org.remus.giteabot.repository.model.ThreadCapabilities;
import org.remus.giteabot.review.CodeReviewService;
import org.remus.giteabot.review.ReviewFence;
import org.remus.giteabot.review.ReviewSkipService;
import org.remus.giteabot.review.state.ReviewFindingRecord;
import org.remus.giteabot.review.state.ReviewFindingRepository;
import org.remus.giteabot.review.state.ReviewFindingStatus;
import org.remus.giteabot.review.state.ReviewRevisionRecord;
import org.remus.giteabot.review.state.ReviewRevisionRepository;
import org.remus.giteabot.review.state.ReviewRunLeaseService;
import org.remus.giteabot.review.state.ReviewStateService;
import org.remus.giteabot.session.ReviewSession;
import org.remus.giteabot.session.SessionService;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GroundedReReviewRegressionTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNode MANIFEST = json("manifest.json");
    private static final JsonNode CASES = json("cases.json");
    private static final JsonNode VERDICTS = json("verdicts.json");
    private static final JsonNode DISPUTES = json("disputes.json");
    private static final String OWNER = "bc3tech";
    private static final String REPO = "sessionregistry";
    private static final String BOT = "gitea-buddy";
    private static final String BASE = MANIFEST.path("base").asText();
    private static final String STALE_TRANSCRIPT = "STALE_TRANSCRIPT: force_args=($RELEASE_FORCE) is a syntax error";

    enum ReviewPath { STANDARD, AGENTIC }

    @ParameterizedTest @EnumSource(ReviewPath.class)
    void initialPushReviewPushReviewDisputeReviewNeverPublishesHistoricalFalsePositives(ReviewPath path) throws Exception {
        Replay replay = new Replay(path);
        replay.output = fixture("review.json");
        replay.revision(0);
        assertThat(replay.run(false)).isTrue();
        replay.revision(1);
        assertThat(replay.run(false)).isTrue();
        replay.assertSkipped();
        replay.revision(2);
        assertThat(replay.run(false)).isTrue();
        replay.assertSkipped();
        ReviewFindingRecord prior = replay.findings.values().iterator().next();
        replay.dispute(prior, "valid");
        assertThat(replay.run(true)).isTrue();

        assertThat(replay.generatedPrompts).hasSize(4).noneMatch(p -> p.contains(STALE_TRANSCRIPT));
        assertThat(replay.findings.values()).singleElement().satisfies(f -> {
            assertThat(f.getStatus()).isEqualTo(ReviewFindingStatus.WITHDRAWN);
            assertThat(f.getHeadSha()).isEqualTo(MANIFEST.path("revisions").get(0).asText());
        });
        assertThat(replay.inline).hasSize(1);
        assertThat(replay.postedBodies()).doesNotContain("force_args=($RELEASE_FORCE)", "force_args+=(-Force -Confirm)",
                CASES.path("intended-fix").path("body").asText());
        assertThat(replay.audits(AuditEventType.FINDING_DROPPED)).hasSize(15);
        assertThat(replay.audits(AuditEventType.FINDING_WITHDRAWN)).hasSize(1);
        assertThat(replay.checkRequests).hasSize(4).allSatisfy(request -> {
            assertThat(request.path("effectiveBaseSha").asText()).isEqualTo(BASE);
            assertThat(request.path("evidenceMode").asText()).isEqualTo("pinned");
            assertThat(request.path("findings").findValuesAsText("body"))
                    .doesNotContain(CASES.path("absent").path("body").asText());
        });
        assertThat(replay.reads).allSatisfy(read -> assertThat(read.ref()).isIn(BASE,
                MANIFEST.path("revisions").get(0).asText(), MANIFEST.path("revisions").get(1).asText(),
                MANIFEST.path("revisions").get(2).asText()));
        assertThat(replay.checkRequests.getLast().toString()).contains(DISPUTES.path("valid").asText());
        verify(replay.sessions, never()).toAiMessages(any());
        verify(replay.leases, times(6)).release(replay.lease);
        replay.assertAuditReasons();
    }

    @ParameterizedTest @EnumSource(ReviewPath.class)
    void absentMisattributedAndIntendedFixAreDroppedWhileValidFindingSurvives(ReviewPath path) throws Exception {
        Replay replay = new Replay(path);
        replay.select("absent", "stale-409", "misattributed", "intended-fix", "valid");
        assertThat(replay.run(false)).isTrue();
        assertThat(replay.inline).singleElement().satisfies(c -> {
            assertThat(c.body()).contains(CASES.path("valid").path("body").asText());
            assertThat(c.line()).isEqualTo(391);
        });
        List<JsonNode> drops = replay.auditPayloads(AuditEventType.FINDING_DROPPED);
        assertThat(drops).hasSize(4);
        assertThat(drops).extracting(p -> p.path("reason").asText()).containsExactlyInAnyOrder(
                "QUOTE_NOT_FOUND", "QUOTE_NOT_FOUND", "QUOTE_MISATTRIBUTED", VERDICTS.path("intended-fix").path("reason").asText());
        assertThat(drops).extracting(p -> p.path("stage").asText()).containsExactlyInAnyOrder("QUOTE", "QUOTE", "QUOTE", "DOUBLE_CHECK");
        JsonNode request = replay.checkRequests.getFirst();
        assertThat(request.path("findings")).hasSize(2);
        JsonNode fix = request.path("findings").get(0);
        assertThat(fix.path("sourceContext").asText()).contains("firstResponse.status === 428").doesNotContain("status === 409");
        assertThat(fix.path("diffHunks").toString()).contains("status === 409", "status === 428");
        assertThat(replay.findings.values()).singleElement().satisfies(f -> {
            assertThat(f.getStatus()).isEqualTo(ReviewFindingStatus.PUBLISHED);
            assertThat(f.isSummaryOnly()).isFalse();
            assertThat(f.getRemoteCommentId()).isNotBlank();
        });
        replay.assertAuditReasons();
    }

    @ParameterizedTest @EnumSource(ReviewPath.class)
    void validDisputeWithdrawsWithLinkedGiteaFallbackAndAudit(ReviewPath path) throws Exception {
        Replay replay = new Replay(path);
        replay.select("valid");
        assertThat(replay.run(false)).isTrue();
        ReviewFindingRecord prior = replay.findings.values().iterator().next();
        replay.select();
        replay.dispute(prior, "valid");
        assertThat(replay.run(true)).isTrue();
        assertThat(prior.getStatus()).isEqualTo(ReviewFindingStatus.WITHDRAWN);
        assertThat(replay.postedBodies()).contains("Withdrawn: " + VERDICTS.path("valid-dispute").path("reason").asText(),
                "[Original finding](https://git.example/pr/66#" + prior.getRemoteCommentId() + ")", prior.getMarker());
        assertThat(replay.auditPayloads(AuditEventType.FINDING_WITHDRAWN)).singleElement().satisfies(a -> {
            assertThat(a.path("stage").asText()).isEqualTo("THREAD");
            assertThat(a.path("findingId").asText()).isEqualTo(prior.getId().toString());
        });
        verify(replay.api, never()).replyToReviewThread(any(), any(), any(), any(), any());
        verify(replay.api, never()).resolveReviewThread(any(), any(), any(), any());
        replay.assertSkipped();
        assertThat(replay.audits(AuditEventType.FINDING_WITHDRAWN)).hasSize(1);
        replay.assertAuditReasons();
    }

    @ParameterizedTest @EnumSource(ReviewPath.class)
    void falseDisputeIsUpheldOnlyOnceAndUnchangedFindingIsNotReposted(ReviewPath path) throws Exception {
        Replay replay = new Replay(path);
        replay.select("valid");
        assertThat(replay.run(false)).isTrue();
        ReviewFindingRecord prior = replay.findings.values().iterator().next();
        String disputeId = replay.dispute(prior, "false");
        assertThat(replay.run(true)).isTrue();
        assertThat(prior.getLastAnsweredCommentId()).isEqualTo(disputeId);
        assertThat(prior.getStatus()).isEqualTo(ReviewFindingStatus.PUBLISHED);
        assertThat(replay.posted.stream().filter(body -> body.startsWith("Finding upheld:"))).hasSize(1);
        assertThat(replay.inline).hasSize(1);
        assertThat(replay.auditPayloads(AuditEventType.FINDING_DROPPED)).singleElement()
                .satisfies(a -> assertThat(a.path("stage").asText()).isEqualTo("DEDUP"));
        replay.assertSkipped();
        assertThat(replay.run(false)).isTrue();
        assertThat(replay.posted.stream().filter(body -> body.startsWith("Finding upheld:"))).hasSize(1);
        assertThat(replay.inline).hasSize(1);
        replay.assertAuditReasons();
    }

    @ParameterizedTest @EnumSource(ReviewPath.class)
    void doubleCheckFailurePublishesOnlyQuoteVerifiedBlockerAndRetriesDegradedRevision(ReviewPath path) throws Exception {
        Replay replay = new Replay(path);
        replay.select("absent", "blocker", "high", "medium");
        replay.checkFailure = true;
        assertThat(replay.run(false)).isTrue();
        assertThat(replay.inline).singleElement().satisfies(c -> assertThat(c.body())
                .contains(CASES.path("blocker").path("body").asText(), "Not double-checked."));
        assertThat(replay.postedBodies()).doesNotContain(CASES.path("high").path("body").asText(),
                CASES.path("medium").path("body").asText(), CASES.path("absent").path("body").asText());
        assertThat(replay.auditPayloads(AuditEventType.FINDING_HELD)).hasSize(2).allSatisfy(a ->
                assertThat(a.path("reason").asText()).isEqualTo("Double-check call failed"));
        assertThat(replay.revisions.values()).singleElement().satisfies(r -> assertThat(r.isDegraded()).isTrue());
        assertThat(replay.run(true)).isTrue();
        assertThat(replay.generatedPrompts).hasSize(2);
        assertThat(replay.inline).hasSize(1);
        replay.assertAuditReasons();
    }

    @ParameterizedTest @EnumSource(ReviewPath.class)
    void reviewWithoutChangesPostsExactlyOneReplyAndDoesNotCallEitherAiStage(ReviewPath path) throws Exception {
        Replay replay = new Replay(path);
        replay.select("valid");
        assertThat(replay.run(false)).isTrue();
        replay.assertSkipped();
        verify(replay.api).removeReaction(OWNER, REPO, replay.payload.getComment().getId(), "eyes");
        verify(replay.api).removePullRequestReaction(OWNER, REPO, 66L, "eyes");
    }

    @ParameterizedTest @EnumSource(ReviewPath.class)
    void removedCodeIsOnlyEvidenceOnThePinnedOldSide(ReviewPath path) throws Exception {
        Replay replay = new Replay(path);
        replay.select("old-side-fix");
        assertThat(replay.run(false)).isTrue();
        assertThat(replay.inline).isEmpty();
        assertThat(replay.findings).isEmpty();
        assertThat(replay.reads).contains(new SourceRead(CASES.path("old-side-fix").path("path").asText(), BASE));
        assertThat(replay.checkRequests.getFirst().path("findings")).singleElement().satisfies(f -> {
            assertThat(f.path("sourceContext").asText()).contains("downloadA.status === 409").doesNotContain("firstResponse.status === 428");
            assertThat(f.path("side").asText()).isEqualTo("OLD");
        });
        assertThat(replay.auditPayloads(AuditEventType.FINDING_DROPPED)).singleElement()
                .satisfies(a -> assertThat(a.path("stage").asText()).isEqualTo("DOUBLE_CHECK"));
        replay.assertAuditReasons();
    }

    @ParameterizedTest @EnumSource(ReviewPath.class)
    void reviewCommandWithAdditionalCommentTextDoesNotHideNewEvidence(ReviewPath path) throws Exception {
        Replay replay = new Replay(path);
        replay.select("valid");
        assertThat(replay.run(false)).isTrue();
        replay.addComment("author", "@" + BOT + " /review\nThere is new evidence to consider.");
        assertThat(replay.run(true)).isTrue();
        assertThat(replay.generatedPrompts).hasSize(2);
        assertThat(replay.inline).hasSize(1);
        replay.assertSkipped();
    }

    private static JsonNode json(String name) {
        try { return MAPPER.readTree(fixture(name)); }
        catch (IOException e) { throw new IllegalStateException(e); }
    }

    private static String fixture(String name) {
        try (InputStream input = GroundedReReviewRegressionTest.class.getResourceAsStream("/grounding/pr66/" + name)) {
            if (input == null) throw new IllegalArgumentException("Missing fixture " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) { throw new IllegalStateException(e); }
    }

    private record SourceRead(String path, String ref) {}

    private static final class Replay {
        private final ReviewPath path;
        private final RepositoryApiClient api = mock(RepositoryApiClient.class);
        private final AiClient ai = mock(AiClient.class);
        private final SessionService sessions = mock(SessionService.class);
        private final ReviewRunLeaseService leases = mock(ReviewRunLeaseService.class);
        private final ReviewRunLeaseService.LeaseToken lease = new ReviewRunLeaseService.LeaseToken(1L, OWNER, REPO, 66L, "replay", 1);
        private final Map<UUID, ReviewFindingRecord> findings = new LinkedHashMap<>();
        private final Map<String, ReviewRevisionRecord> revisions = new LinkedHashMap<>();
        private final List<PrAuditEvent> events = new ArrayList<>();
        private final List<Map<String, Object>> comments = new ArrayList<>();
        private final List<ReviewComment> threads = new ArrayList<>();
        private final List<ReviewAnchorComment> inline = new ArrayList<>();
        private final List<String> posted = new ArrayList<>();
        private final List<String> generatedPrompts = new ArrayList<>();
        private final List<JsonNode> checkRequests = new ArrayList<>();
        private final List<SourceRead> reads = new ArrayList<>();
        private final WebhookPayload payload;
        private final CodeReviewService standard;
        private final AgentReviewService agent;
        private ReviewSnapshot snapshot;
        private String output;
        private boolean checkFailure;
        private long remoteId = 100;
        private long runId;

        Replay(ReviewPath path) throws Exception {
            this.path = path;
            payload = MAPPER.readValue("""
                    {"repository":{"name":"sessionregistry","owner":{"login":"bc3tech"}},
                     "pull_request":{"number":66,"title":"Plugin release overrides and download test fix",
                     "head":{"ref":"feature/plugin-release"}}}
                    """, WebhookPayload.class);
            when(leases.acquire(1L, OWNER, REPO, 66L)).thenReturn(lease);
            ReviewFindingRepository findingRepo = mock(ReviewFindingRepository.class);
            when(findingRepo.save(any())).thenAnswer(inv -> {
                ReviewFindingRecord finding = inv.getArgument(0); findings.put(finding.getId(), finding); return finding;
            });
            when(findingRepo.findById(any())).thenAnswer(inv -> Optional.ofNullable(findings.get(inv.getArgument(0))));
            when(findingRepo.findByBotIdAndRepoOwnerAndRepoNameAndPrNumberAndWorkflowKeyAndStatusInOrderByCreatedAtAsc(
                    eq(1L), eq(OWNER), eq(REPO), eq(66L), anyString(), anyCollection())).thenAnswer(inv -> {
                Collection<ReviewFindingStatus> statuses = inv.getArgument(5);
                return findings.values().stream().filter(f -> f.getWorkflowKey().equals(inv.getArgument(4))
                        && statuses.contains(f.getStatus())).toList();
            });
            ReviewRevisionRepository revisionRepo = mock(ReviewRevisionRepository.class);
            when(revisionRepo.findByBotIdAndRepoOwnerAndRepoNameAndPrNumberAndWorkflowKey(
                    eq(1L), eq(OWNER), eq(REPO), eq(66L), anyString()))
                    .thenAnswer(inv -> Optional.ofNullable(revisions.get(inv.getArgument(4))));
            when(revisionRepo.save(any())).thenAnswer(inv -> {
                ReviewRevisionRecord revision = inv.getArgument(0); revisions.put(revision.getWorkflowKey(), revision); return revision;
            });
            ReviewStateService state = new ReviewStateService(findingRepo, revisionRepo, leases);
            PrAuditEventRepository auditRepo = mock(PrAuditEventRepository.class);
            when(auditRepo.save(any())).thenAnswer(inv -> {
                PrAuditEvent event = inv.getArgument(0); event.setId((long) events.size() + 1); events.add(event); return event;
            });
            when(auditRepo.findTopByRunIdOrderByIdDesc(anyLong())).thenAnswer(inv -> events.stream()
                    .filter(e -> e.getRunId().equals(inv.getArgument(0))).reduce((first, second) -> second).orElse(null));
            PrAuditEventService audit = new PrAuditEventService(auditRepo);
            PriorFindingsService priors = new PriorFindingsService(state);
            FindingGroundingService grounding = new FindingGroundingService(new DoubleCheckService(
                    mock(AiIntegrationService.class), mock(AiClientFactory.class), audit), audit);
            ThreadActionExecutor executor = new ThreadActionExecutor(state, leases, audit);
            ReviewSkipService skip = new ReviewSkipService(state, leases, mock(GiteaClientFactory.class));
            configureProvider();
            when(ai.submitReviewPrompt(anyString(), isNull(), anyString())).thenAnswer(inv -> {
                String prompt = inv.getArgument(2);
                if (prompt.startsWith("{")) return doubleCheck(prompt);
                generatedPrompts.add(prompt); return output;
            });
            when(ai.supportsNativeTools()).thenReturn(true);
            when(ai.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt())).thenAnswer(inv -> {
                List<AiMessage> history = inv.getArgument(0);
                assertThat(history).noneMatch(m -> m.getContent() != null && m.getContent().contains(STALE_TRANSCRIPT));
                generatedPrompts.add(inv.getArgument(1));
                return new ChatTurn(output, List.of(), StopReason.END_TURN, 100, 100);
            });
            ReviewSession session = new ReviewSession(OWNER, REPO, 66L, "prompt:replay");
            session.addMessage("assistant", STALE_TRANSCRIPT);
            when(sessions.getOrCreateSession(OWNER, REPO, 66L, "prompt:replay")).thenReturn(session);
            doAnswer(inv -> { session.addMessage(inv.getArgument(1), inv.getArgument(2)); return null; })
                    .when(sessions).addMessage(eq(session), anyString(), anyString());
            standard = new CodeReviewService(api, ai, sessions, BOT, new ReviewConfigProperties(), "prompt:replay", "Review",
                    120000, 8, 60000, "", DoubleCheckSettings.defaults(), 1L, leases, state, priors, grounding,
                    executor, audit, skip, Duration.ofMinutes(10));
            AgentConfigProperties config = new AgentConfigProperties();
            config.getBudget().setMaxRounds(3);
            ToolCatalog catalog = new ToolCatalog(config);
            WorkspaceService workspace = mock(WorkspaceService.class);
            when(workspace.prepareWorkspaceAtRevision(eq(api), eq(OWNER), eq(REPO), anyString(), eq(66L)))
                    .thenReturn(WorkspaceResult.success(Path.of("pr66-replay-workspace").toAbsolutePath()));
            Bot bot = new Bot(); bot.setId(1L); bot.setUsername(BOT);
            agent = new AgentReviewService(new AgentReviewContext(api, ai, "Review", BOT, null, null, Set.of("pr-diff"), 200000),
                    new AgentSessionService(mock(AgentSessionRepository.class)),
                    new ToolExecutionService(config, catalog, workspace), catalog, workspace, config, null, bot,
                    mock(EventHookPublisher.class), leases, state, priors, grounding, executor, skip, audit,
                    Duration.ofMinutes(10), DoubleCheckSettings.defaults(), new DoubleCheckService.Budget(120000, 8));
            revision(2);
        }

        private void configureProvider() {
            when(api.getReviewSnapshot(OWNER, REPO, 66L)).thenAnswer(inv -> snapshot);
            when(api.getPullRequestDiff(OWNER, REPO, 66L)).thenAnswer(inv -> snapshot.diff());
            when(api.getFileContent(eq(OWNER), eq(REPO), anyString(), anyString())).thenAnswer(inv -> {
                String file = inv.getArgument(2); String sha = inv.getArgument(3); reads.add(new SourceRead(file, sha));
                String source = MANIFEST.path("paths").path("workflow.source").asText().equals(file) ? "workflow.source"
                        : MANIFEST.path("paths").path("viewer.source").asText().equals(file) ? "viewer.source" : null;
                if (source == null) throw new IllegalArgumentException("Unexpected source read: " + file);
                int firstLine = MANIFEST.path("sourceStartLines").path(sha).path(source).asInt();
                return "\n".repeat(firstLine - 1) + fixture(sha + "/" + source);
            });
            when(api.listPullRequestComments(OWNER, REPO, 66L)).thenAnswer(inv -> new CommentScan<>(List.copyOf(comments), true));
            when(api.listReviewThreadComments(OWNER, REPO, 66L)).thenAnswer(inv -> new CommentScan<>(List.copyOf(threads), true));
            when(api.threadCapabilities()).thenReturn(ThreadCapabilities.unsupported());
            when(api.pullRequestCommentLink(eq(OWNER), eq(REPO), eq(66L), anyString()))
                    .thenAnswer(inv -> Optional.of("https://git.example/pr/66#" + inv.getArgument(3)));
            when(api.postReviewReturningId(eq(OWNER), eq(REPO), eq(66L), anyString(), any()))
                    .thenAnswer(inv -> Optional.of(post(inv.getArgument(3))));
            when(api.postPullRequestCommentReturningId(eq(OWNER), eq(REPO), eq(66L), anyString()))
                    .thenAnswer(inv -> Optional.of(post(inv.getArgument(3))));
            doAnswer(inv -> { post(inv.getArgument(3)); return null; })
                    .when(api).postPullRequestComment(eq(OWNER), eq(REPO), eq(66L), anyString());
            when(api.publishInlineReview(eq(OWNER), eq(REPO), eq(66L), any(), anyString(), anyList(), any())).thenAnswer(inv -> {
                assertThat((ReviewSnapshot) inv.getArgument(3)).isEqualTo(snapshot);
                post(inv.getArgument(4));
                List<ReviewAnchorComment> requested = inv.getArgument(5);
                List<ReviewPublicationResult.CommentOutcome> outcomes = new ArrayList<>();
                for (ReviewAnchorComment anchor : requested) {
                    inline.add(anchor);
                    long id = ++remoteId;
                    ReviewComment comment = mock(ReviewComment.class);
                    when(comment.getId()).thenReturn(id); when(comment.getBody()).thenReturn(anchor.body());
                    when(comment.getUserLogin()).thenReturn(BOT); when(comment.getThreadId()).thenReturn("thread-" + id);
                    threads.add(comment);
                    outcomes.add(new ReviewPublicationResult.CommentOutcome(anchor, ReviewPublicationResult.Delivery.CONFIRMED,
                            Long.toString(id)));
                }
                return new ReviewPublicationResult(ReviewPublicationResult.Status.PUBLISHED,
                        ReviewPublicationResult.Delivery.CONFIRMED, outcomes, null);
            });
        }

        void revision(int index) {
            String sha = MANIFEST.path("revisions").get(index).asText();
            snapshot = new ReviewSnapshot(sha, "moving-target-tip", BASE, fixture(sha + "/review.diff"), true);
        }

        void select(String... labels) throws IOException {
            ObjectNode envelope = MAPPER.createObjectNode().put("summary", "PR 66 replay");
            ArrayNode selected = envelope.putArray("findings");
            for (String label : labels) selected.add(CASES.path(label));
            output = MAPPER.writeValueAsString(envelope);
        }

        boolean run(boolean explicit) {
            if (explicit) {
                long id = addComment("author", "@" + BOT + " /review");
                WebhookPayload.Comment trigger = new WebhookPayload.Comment();
                trigger.setId(id); trigger.setBody("@" + BOT + " /review"); payload.setComment(trigger);
            } else payload.setComment(null);
            runId++;
            if (path == ReviewPath.STANDARD) return standard.runReview(payload, null, runId, explicit, null, ReviewFence.NONE).reviewed();
            AgentReviewService.ReviewResult result = agent.reviewPullRequest(payload, 1, false, null,
                    new AgentReviewService.SeverityThresholds(null, null, null), runId, null, ReviewFence.NONE, explicit);
            assertThat(result).isIn(AgentReviewService.ReviewResult.POSTED, AgentReviewService.ReviewResult.SKIPPED);
            return result == AgentReviewService.ReviewResult.POSTED;
        }

        void assertSkipped() {
            int writes = posted.size(); int generated = generatedPrompts.size(); int checked = checkRequests.size();
            assertThat(run(true)).isFalse();
            assertThat(posted.subList(writes, posted.size())).containsExactly(
                    "No changes since last review at `" + snapshot.headSha().substring(0, 7) + "`.");
            assertThat(generatedPrompts).hasSize(generated); assertThat(checkRequests).hasSize(checked);
        }

        String dispute(ReviewFindingRecord prior, String label) {
            return Long.toString(addComment("author", DISPUTES.path(label).asText() + "\n\n" + prior.getMarker()));
        }

        private long addComment(String login, String body) {
            long id = ++remoteId;
            comments.add(Map.of("id", id, "body", body, "user", Map.of("login", login),
                    "created_at", Instant.parse("2026-10-08T00:00:00Z").plusSeconds(id).toString()));
            return id;
        }

        private String post(String body) {
            posted.add(body); return Long.toString(addComment(BOT, body));
        }

        private String doubleCheck(String prompt) throws IOException {
            JsonNode request = MAPPER.readTree(prompt); checkRequests.add(request);
            if (checkFailure) throw new IllegalStateException("Canned model unavailable");
            ObjectNode response = MAPPER.createObjectNode(); ArrayNode verdicts = response.putArray("verdicts");
            for (JsonNode finding : request.path("findings")) {
                String label = finding.path("body").asText().equals(CASES.path("intended-fix").path("body").asText())
                        ? "intended-fix" : "valid";
                String dispute = finding.path("disputeText").asText();
                if (finding.path("prior").asBoolean() && !dispute.isBlank()) {
                    label = dispute.contains(DISPUTES.path("valid").asText()) ? "valid-dispute" : "false-dispute";
                }
                ObjectNode verdict = VERDICTS.path(label).deepCopy(); verdict.put("id", finding.path("id").asText());
                verdicts.add(verdict);
            }
            return MAPPER.writeValueAsString(response);
        }

        String postedBodies() { return String.join("\n", posted) + inline.stream().map(ReviewAnchorComment::body).toList(); }

        List<PrAuditEvent> audits(AuditEventType type) { return events.stream().filter(e -> e.getEventType() == type).toList(); }

        List<JsonNode> auditPayloads(AuditEventType type) throws IOException {
            List<JsonNode> payloads = new ArrayList<>();
            for (PrAuditEvent event : audits(type)) payloads.add(MAPPER.readTree(event.getEventPayloadJson()));
            return payloads;
        }

        void assertAuditReasons() throws IOException {
            for (AuditEventType type : List.of(AuditEventType.FINDING_DROPPED, AuditEventType.FINDING_HELD,
                    AuditEventType.FINDING_WITHDRAWN)) {
                assertThat(audits(type)).allSatisfy(e -> { assertThat(e.getRunId()).isPositive(); assertThat(e.getHash()).hasSize(64); });
                assertThat(auditPayloads(type)).allSatisfy(a -> {
                    assertThat(a.path("findingId").asText()).isNotBlank(); assertThat(a.path("reason").asText()).isNotBlank();
                    assertThat(a.path("headSha").asText()).isNotBlank();
                    assertThat(a.path("effectiveBaseSha").asText()).isEqualTo(BASE);
                    assertThat(a.path("quoteHash").asText()).hasSize(64);
                });
            }
        }
    }
}
