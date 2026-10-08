package org.remus.giteabot.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class PrAuditEventService {

    static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private final PrAuditEventRepository auditRepository;

    /** Serializes a payload map to canonical JSON (sorted keys). Returns null for null/empty input. */
    public static String toJson(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) return null;
        try {
            return CANONICAL_MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Persists an audit event after computing its hash from the previous event in the chain.
     * Failures are logged and never propagated.
     */
    @Transactional
    public void record(PrAuditEvent event) {
        try {
            String previousHash = resolvePreviousHash(event);
            String payloadJson = event.getEventPayloadJson();
            String eventTypeName = event.getEventType() != null ? event.getEventType().name() : "UNKNOWN";
            event.setPreviousHash(previousHash);
            event.setHash(computeHash(eventTypeName, event.getEventTimestamp(), payloadJson, previousHash));
            auditRepository.save(event);
            log.debug("Audit event persisted: type={} runId={} id={}", event.getEventType(), event.getRunId(), event.getId());
        } catch (Exception e) {
            log.error("Failed to persist audit event type={} runId={}: {}", event.getEventType(), event.getRunId(), e.getMessage(), e);
        }
    }

    /** Convenience: emit a tool-call event. Arguments truncated to 2 KB, result to 1 KB. */
    @Transactional
    public void emitToolCall(Long botId, String repoOwner, String repoName, Long prNumber,
                             Long runId, Long aiSessionId, String aiUsageSessionId,
                             int round, String toolName, String arguments,
                             String resultExcerpt, boolean success, long durationMs,
                             Long inputTokens, Long outputTokens) {
        String args = arguments != null && arguments.length() > 2000
                ? arguments.substring(0, 1997) + "..." : arguments;
        String result = resultExcerpt != null && resultExcerpt.length() > 1024
                ? resultExcerpt.substring(0, 1021) + "..." : resultExcerpt;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool_name", toolName);
        payload.put("arguments", args);
        payload.put("result_excerpt", result);
        payload.put("success", success);
        payload.put("round", round);
        try {
            String payloadJson = CANONICAL_MAPPER.writeValueAsString(payload);
            PrAuditEvent event = PrAuditEvent.builder()
                    .eventType(AuditEventType.TOOL_CALL_EXECUTED)
                    .eventTimestamp(Instant.now())
                    .botId(botId).repoOwner(repoOwner).repoName(repoName).prNumber(prNumber)
                    .runId(runId)
                    .actorType(ActorType.BOT.name()).actorId("agent")
                    .aiSessionId(aiSessionId).aiUsageSessionId(aiUsageSessionId)
                    .eventPayloadJson(payloadJson)
                    .durationMs(durationMs)
                    .inputTokens(inputTokens).outputTokens(outputTokens)
                    .build();
            record(event);
        } catch (Exception e) {
            log.error("Failed to persist tool-call audit event runId={}: {}", runId, e.getMessage(), e);
        }
    }


    /** Records one dropped, held, or withdrawn finding with its evidence and disposition reason. */
    @Transactional
    public void recordFindingDisposition(PrAuditEvent event, AuditEventType dispositionType,
                                         String findingId, String path, String side, Integer line,
                                         String quote, String severity, String category, String stage,
                                         String reason, String headSha, String effectiveBaseSha) {
        if (dispositionType != AuditEventType.FINDING_DROPPED
                && dispositionType != AuditEventType.FINDING_HELD
                && dispositionType != AuditEventType.FINDING_WITHDRAWN) {
            throw new IllegalArgumentException("dispositionType must be a finding disposition event type");
        }
        requireRun(event);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("findingId", findingId);
        payload.put("path", path);
        payload.put("side", side);
        payload.put("line", line);
        payload.put("quoteHash", sha256(quote));
        payload.put("quotePreview", preview(quote));
        payload.put("severity", severity);
        payload.put("category", category);
        payload.put("stage", stage);
        payload.put("reason", reason);
        payload.put("headSha", headSha);
        payload.put("effectiveBaseSha", effectiveBaseSha);

        event.setEventType(dispositionType);
        event.setEventPayloadJson(toJson(payload));
        record(event);
    }

    /** Records a run-level warning that affected grounding behavior. */
    @Transactional
    public void recordGroundingWarning(PrAuditEvent event, String stage, String reason,
                                      String headSha, String effectiveBaseSha) {
        requireRun(event);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stage", stage);
        payload.put("reason", reason);
        payload.put("headSha", headSha);
        payload.put("effectiveBaseSha", effectiveBaseSha);

        event.setEventType(AuditEventType.GROUNDING_WARNING);
        event.setEventPayloadJson(toJson(payload));
        record(event);
    }

    private static void requireRun(PrAuditEvent event) {
        if (event == null) throw new IllegalArgumentException("event must not be null");
        if (event.getRunId() == null) throw new IllegalArgumentException("runId must not be null");
    }

    private static String preview(String quote) {
        return quote != null && quote.length() > 200 ? quote.substring(0, 200) : quote;
    }

    private static String sha256(String value) {
        if (value == null) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    @Transactional(readOnly = true)
    public List<PrAuditEvent> findByBotAndRepoAndPr(Long botId, String repoOwner,
                                                     String repoName, Long prNumber) {
        return auditRepository.findByBotIdAndRepoOwnerAndRepoNameAndPrNumberOrderByIdAsc(
                botId, repoOwner, repoName, prNumber);
    }

    @Transactional(readOnly = true)
    public List<PrAuditEvent> findByBot(Long botId) {
        return auditRepository.findByBotIdOrderByIdAsc(botId);
    }

    private String resolvePreviousHash(PrAuditEvent event) {
        PrAuditEvent previous;
        if (event.getRunId() != null) {
            previous = auditRepository.findTopByRunIdOrderByIdDesc(event.getRunId());
        } else {
            previous = auditRepository.findTopByBotIdAndRepoOwnerAndRepoNameAndPrNumberOrderByIdDesc(
                    event.getBotId(), event.getRepoOwner(), event.getRepoName(), event.getPrNumber());
        }
        return previous != null ? previous.getHash() : null;
    }

    static String computeHash(String eventType, Instant timestamp, String payloadJson, String previousHash) {
        String input = eventType
                + "|" + (timestamp != null ? timestamp.toEpochMilli() : "0")
                + "|" + (payloadJson != null ? payloadJson : "")
                + "|" + (previousHash != null ? previousHash : "NULL");
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
