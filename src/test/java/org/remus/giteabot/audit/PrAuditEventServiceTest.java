package org.remus.giteabot.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.jdbc.Sql;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Sql(statements = "DELETE FROM pr_audit_events", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class PrAuditEventServiceTest {

    @Autowired
    private PrAuditEventService auditService;

    @Test
    void shouldEmitAndPersistAuditEvent() {
        auditService.record(PrAuditEvent.builder()
                .eventType(AuditEventType.PR_WORKFLOW_RUN_STARTED)
                .eventTimestamp(Instant.now())
                .botId(1L).repoOwner("emitter").repoName("repo-a").prNumber(1L)
                .actorType(ActorType.SYSTEM.name()).actorId("test")
                .eventPayloadJson(PrAuditEventService.toJson(Map.of("workflow_key", "review")))
                .build());

        List<PrAuditEvent> events = auditService.findByBotAndRepoAndPr(1L, "emitter", "repo-a", 1L);
        assertThat(events).hasSize(1);
        PrAuditEvent event = events.getFirst();
        assertThat(event.getEventType()).isEqualTo(AuditEventType.PR_WORKFLOW_RUN_STARTED);
        assertThat(event.getHash()).isNotNull().hasSize(64);
        assertThat(event.getPreviousHash()).isNull();
    }

    @Test
    void shouldProduceDeterministicHash() {
        String hash1 = PrAuditEventService.computeHash("TEST", Instant.EPOCH, "{\"a\":\"1\"}", null);
        String hash2 = PrAuditEventService.computeHash("TEST", Instant.EPOCH, "{\"a\":\"1\"}", null);
        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    void shouldChainHashesAcrossEvents() {
        auditService.record(PrAuditEvent.builder()
                .eventType(AuditEventType.PR_WORKFLOW_RUN_STARTED)
                .eventTimestamp(Instant.now())
                .botId(1L).repoOwner("chainer").repoName("repo-b").prNumber(1L)
                .actorType(ActorType.SYSTEM.name()).actorId("test")
                .build());

        auditService.record(PrAuditEvent.builder()
                .eventType(AuditEventType.PR_WORKFLOW_STEP_APPENDED)
                .eventTimestamp(Instant.now())
                .botId(1L).repoOwner("chainer").repoName("repo-b").prNumber(1L)
                .actorType(ActorType.SYSTEM.name()).actorId("test")
                .eventPayloadJson(PrAuditEventService.toJson(Map.of("step", 1)))
                .build());

        List<PrAuditEvent> events = auditService.findByBotAndRepoAndPr(1L, "chainer", "repo-b", 1L);
        assertThat(events).hasSize(2);
        assertThat(events.get(0).getPreviousHash()).isNull();
        assertThat(events.get(1).getPreviousHash()).isEqualTo(events.get(0).getHash());
    }

    @Test
    void shouldRecordFindingDispositionsAndGroundingWarnings() throws Exception {
        PrAuditEventRepository repository = mock(PrAuditEventRepository.class);
        List<PrAuditEvent> persisted = new ArrayList<>();
        when(repository.findTopByRunIdOrderByIdDesc(77L))
                .thenAnswer(invocation -> persisted.isEmpty() ? null : persisted.getLast());
        when(repository.save(any(PrAuditEvent.class))).thenAnswer(invocation -> {
            PrAuditEvent event = invocation.getArgument(0);
            persisted.add(event);
            return event;
        });
        PrAuditEventService service = new PrAuditEventService(repository);
        String longQuote = "quoted source ".repeat(20);
        AuditEventType[] dispositionTypes = {
                AuditEventType.FINDING_DROPPED,
                AuditEventType.FINDING_HELD,
                AuditEventType.FINDING_WITHDRAWN
        };

        for (int i = 0; i < dispositionTypes.length; i++) {
            String quote = i == 0 ? longQuote : "quoted source";
            service.recordFindingDisposition(auditContext(), dispositionTypes[i],
                    "finding-" + i, "src/Main.java", "RIGHT", 17, quote,
                    "HIGH", "correctness", "QUOTE", "quote not found", "head-sha", "base-sha");
        }
        for (String reason : List.of("config fallback", "unparseable output", "lease lost")) {
            service.recordGroundingWarning(auditContext(), "GROUNDING", reason, "head-sha", "base-sha");
        }

        assertThat(persisted).hasSize(6);
        ObjectMapper mapper = new ObjectMapper();
        for (int i = 0; i < dispositionTypes.length; i++) {
            PrAuditEvent event = persisted.get(i);
            JsonNode payload = mapper.readTree(event.getEventPayloadJson());
            assertThat(event.getEventType()).isEqualTo(dispositionTypes[i]);
            assertThat(event.getRunId()).isEqualTo(77L);
            assertThat(payload.size()).isEqualTo(12);
            for (String key : List.of("findingId", "path", "side", "line", "quoteHash", "quotePreview",
                    "severity", "category", "stage", "reason", "headSha", "effectiveBaseSha")) {
                assertThat(payload.has(key)).isTrue();
            }
            assertThat(payload.get("findingId").asText()).isEqualTo("finding-" + i);
            assertThat(payload.get("reason").asText()).isEqualTo("quote not found");
            if (i == 0) {
                String expectedHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(longQuote.getBytes(StandardCharsets.UTF_8)));
                assertThat(payload.get("quoteHash").asText()).isEqualTo(expectedHash);
                assertThat(payload.get("quotePreview").asText()).hasSize(200);
            }
        }
        for (int i = 3; i < persisted.size(); i++) {
            PrAuditEvent event = persisted.get(i);
            JsonNode payload = mapper.readTree(event.getEventPayloadJson());
            assertThat(event.getEventType()).isEqualTo(AuditEventType.GROUNDING_WARNING);
            assertThat(payload.get("reason").asText())
                    .isEqualTo(List.of("config fallback", "unparseable output", "lease lost").get(i - 3));
        }
        for (int i = 0; i < persisted.size(); i++) {
            PrAuditEvent event = persisted.get(i);
            assertThat(event.getHash()).hasSize(64);
            assertThat(event.getPreviousHash()).isEqualTo(i == 0 ? null : persisted.get(i - 1).getHash());
        }
    }

    @Test
    void reusedRunContextKeepsAllEventsAndHashChain() {
        PrAuditEventRepository repository = mock(PrAuditEventRepository.class);
        List<PrAuditEvent> persisted = new ArrayList<>();
        when(repository.findTopByRunIdOrderByIdDesc(77L))
                .thenAnswer(inv -> persisted.isEmpty() ? null : persisted.getLast());
        when(repository.save(any(PrAuditEvent.class))).thenAnswer(inv -> {
            PrAuditEvent event = inv.getArgument(0);
            if (event.getId() == null) {
                event.setId((long) persisted.size() + 1);
                persisted.add(event);
            }
            return event;
        });
        PrAuditEventService service = new PrAuditEventService(repository);
        PrAuditEvent context = auditContext();
        service.recordFindingDisposition(context, AuditEventType.FINDING_DROPPED,
                "first", "A.java", "NEW", 1, "return value();", "HIGH", "bug", "QUOTE",
                "missing quote", "head", "base");
        service.recordFindingDisposition(context, AuditEventType.FINDING_HELD,
                "second", "B.java", "NEW", 2, "validateAccess();", "HIGH", "bug", "DOUBLE_CHECK",
                "uncertain", "head", "base");
        service.recordGroundingWarning(context, "THREAD", "retry action", "head", "base");

        List<PrAuditEvent> events = persisted;
        assertThat(events).hasSize(3);
        assertThat(events).extracting(PrAuditEvent::getEventType).containsExactly(
                AuditEventType.FINDING_DROPPED, AuditEventType.FINDING_HELD, AuditEventType.GROUNDING_WARNING);
        assertThat(events).extracting(PrAuditEvent::getId).doesNotHaveDuplicates();
        assertThat(events.getFirst().getPreviousHash()).isNull();
        for (int i = 1; i < events.size(); i++) {
            assertThat(events.get(i).getPreviousHash()).isEqualTo(events.get(i - 1).getHash());
        }
        assertThat(context.getId()).isNull();
        assertThat(context.getEventType()).isNull();
    }

    private static PrAuditEvent auditContext() {
        return PrAuditEvent.builder()
                .botId(1L).repoOwner("auditor").repoName("repo").prNumber(2L).runId(77L)
                .actorType(ActorType.BOT.name()).actorId("grounding")
                .build();
    }

    @Test
    void shouldQueryByBotRepoPr() {
        auditService.record(PrAuditEvent.builder()
                .eventType(AuditEventType.PR_WORKFLOW_RUN_STARTED)
                .botId(1L).repoOwner("botr1").repoName("repo-c").prNumber(1L)
                .actorType(ActorType.SYSTEM.name()).actorId("t").build());
        auditService.record(PrAuditEvent.builder()
                .eventType(AuditEventType.PR_WORKFLOW_RUN_STARTED)
                .botId(2L).repoOwner("botr2").repoName("repo-c").prNumber(2L)
                .actorType(ActorType.SYSTEM.name()).actorId("t").build());

        List<PrAuditEvent> events = auditService.findByBotAndRepoAndPr(1L, "botr1", "repo-c", 1L);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().getBotId()).isEqualTo(1L);
    }

    @Test
    void shouldQueryByBot() {
        auditService.record(PrAuditEvent.builder()
                .eventType(AuditEventType.PR_WORKFLOW_RUN_STARTED)
                .botId(1L).repoOwner("bot-unique").repoName("repo-d").prNumber(1L)
                .actorType(ActorType.SYSTEM.name()).actorId("t").build());

        List<PrAuditEvent> events = auditService.findByBot(1L);
        assertThat(events).hasSize(1);
    }

    @Test
    void shouldEmitStepEventWithStepMetadata() {
        auditService.record(PrAuditEvent.builder()
                .eventType(AuditEventType.PR_WORKFLOW_STEP_APPENDED)
                .eventTimestamp(Instant.now())
                .botId(1L).repoOwner("stepper").repoName("repo-e").prNumber(1L)
                .actorType(ActorType.SYSTEM.name()).actorId("test")
                .eventPayloadJson(PrAuditEventService.toJson(Map.of("run_id", 46L, "step_order", 0)))
                .stepIndex(0).stepName("fetch-diff").stepStatus("INFO")
                .build());

        List<PrAuditEvent> events = auditService.findByBotAndRepoAndPr(1L, "stepper", "repo-e", 1L);
        assertThat(events).hasSize(1);
        PrAuditEvent e = events.getFirst();
        assertThat(e.getStepIndex()).isEqualTo(0);
        assertThat(e.getStepName()).isEqualTo("fetch-diff");
        assertThat(e.getStepStatus()).isEqualTo("INFO");
    }
}
