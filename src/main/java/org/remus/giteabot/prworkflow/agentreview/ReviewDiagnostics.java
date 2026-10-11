package org.remus.giteabot.prworkflow.agentreview;

import org.remus.giteabot.agent.loop.AgentRunContext;
import org.remus.giteabot.agent.loop.ToolingMode;
import org.remus.giteabot.agent.model.ImplementationPlan;
import org.remus.giteabot.ai.StopReason;
import org.remus.giteabot.ai.ToolCall;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Safe, review-local diagnostics. No raw request, response, or repository content is retained. */
public final class ReviewDiagnostics {

    public enum Category {
        CONTEXT_ROUND_LIMIT("context-round-limit"),
        LOOP_ROUND_LIMIT("loop-round-limit"),
        OUTPUT_TOKEN_LIMIT("output-token-limit"),
        INCOMPLETE_COMPLETION("incomplete-completion"),
        UNKNOWN_COMPLETION("unknown-completion"),
        EMPTY_FEEDBACK("empty-feedback"),
        STALLED_CONTEXT("stalled-context"),
        MANDATORY_CONTEXT_LIMIT("mandatory-context-limit"),
        SETUP_FAILURE("setup-failure"),
        PROVIDER_REQUEST_FAILURE("provider-request-failure"),
        PUBLICATION_FAILURE("publication-failure"),
        UNKNOWN_EXECUTION_FAILURE("unknown-execution-failure");

        private final String label;

        Category(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum NoticeStatus {
        NOT_ATTEMPTED, DELIVERED, FAILED, UNAVAILABLE
    }

    public record Snapshot(
            Category category,
            int totalTurns,
            int contextRounds,
            Integer contextLimit,
            Integer loopLimit,
            Integer outputTokenLimit,
            Long outputTokens,
            ToolingMode mode,
            StopReason completionReason,
            Integer promptCharsEstimate,
            long responseCharacters,
            long contextCharacters,
            int requestOccurrences,
            int repeatedRequestOccurrences,
            int repeatedRequestRounds,
            boolean providerCallFailed,
            Long runId,
            String owner,
            String repo,
            Long pullRequestNumber,
            String provider,
            Long integrationId,
            int executedRequestOccurrences,
            int suppressedRequestOccurrences
    ) {
        public Snapshot(Category category, int totalTurns, int contextRounds, Integer contextLimit,
                        Integer loopLimit, Integer outputTokenLimit, Long outputTokens, ToolingMode mode,
                        StopReason completionReason, Integer promptCharsEstimate, long responseCharacters,
                        long contextCharacters, int requestOccurrences, int repeatedRequestOccurrences,
                        int repeatedRequestRounds, boolean providerCallFailed, Long runId, String owner,
                        String repo, Long pullRequestNumber, String provider, Long integrationId) {
            this(category, totalTurns, contextRounds, contextLimit, loopLimit, outputTokenLimit,
                    outputTokens, mode, completionReason, promptCharsEstimate, responseCharacters,
                    contextCharacters, requestOccurrences, repeatedRequestOccurrences, repeatedRequestRounds,
                    providerCallFailed, runId, owner, repo, pullRequestNumber, provider, integrationId, 0, 0);
        }

        public String summary() {
            return switch (category) {
                case CONTEXT_ROUND_LIMIT -> category.label() + " (" + contextRounds + "/"
                        + available(contextLimit) + " context rounds; " + totalTurns + " total turns)";
                case LOOP_ROUND_LIMIT -> category.label() + " (" + totalTurns + "/"
                        + available(loopLimit) + " total turns)";
                case OUTPUT_TOKEN_LIMIT -> category.label() + " (output limit "
                        + available(outputTokenLimit) + "; reported usage " + available(outputTokens) + ")";
                default -> category.label();
            };
        }

        public String details() {
            return "category=" + category.label()
                    + "; turns=" + totalTurns
                    + "; contextRounds=" + contextRounds + "/" + contextLimitLabel()
                    + "; loopRounds=" + totalTurns + "/" + available(loopLimit)
                    + "; outputTokenLimit=" + available(outputTokenLimit)
                    + "; outputTokens=" + available(outputTokens)
                    + "; mode=" + (mode == null ? "unavailable" : mode.name().toLowerCase())
                    + "; completion=" + (completionReason == null ? "unavailable" : completionReason.name())
                    + "; promptCharsEstimate=" + available(promptCharsEstimate)
                    + "; responseChars=" + responseCharacters
                    + "; contextChars=" + contextCharacters
                    + "; requestOccurrences=" + requestOccurrences
                    + "; executedRequestOccurrences=" + executedRequestOccurrences
                    + "; suppressedRequestOccurrences=" + suppressedRequestOccurrences
                    + "; repeatedRequestOccurrences=" + repeatedRequestOccurrences
                    + "; repeatedRequestRounds=" + repeatedRequestRounds
                    + "; providerCallFailed=" + providerCallFailed
                    + "; provider=" + available(provider)
                    + "; integrationId=" + available(integrationId)
                    + "; runId=" + available(runId)
                    + "; PR=" + available(owner) + "/" + available(repo) + "#"
                    + available(pullRequestNumber);
        }

        private String contextLimitLabel() {
            if (contextLimit != null) {
                return contextLimit.toString();
            }
            return mode == ToolingMode.NATIVE ? "not-enforced" : "unavailable";
        }

        private static String available(Object value) {
            return value == null ? "unavailable" : value.toString();
        }
    }

    /** Per-invocation counters and keyed request fingerprints. */
    public static final class Collector {
        private final Integer contextLimit;
        private final Integer loopLimit;
        private final Integer outputTokenLimit;
        private final Long runId;
        private final String owner;
        private final String repo;
        private final Long pullRequestNumber;
        private final String provider;
        private final Long integrationId;
        private final byte[] fingerprintKey = new byte[32];
        private final Map<String, Integer> requestCounts = new HashMap<>();
        private final Set<Integer> repeatedRounds = new HashSet<>();
        private int totalTurns;
        private int contextRounds;
        private Long outputTokens;
        private ToolingMode mode;
        private StopReason completionReason;
        private Integer promptCharsEstimate;
        private long responseCharacters;
        private long contextCharacters;
        private int requestOccurrences;
        private int repeatedRequestOccurrences;
        private int executedRequestOccurrences;
        private int suppressedRequestOccurrences;
        private boolean providerCallFailed;

        public Collector(Integer contextLimit, Integer loopLimit, Integer outputTokenLimit,
                         Long runId, String owner, String repo, Long pullRequestNumber,
                         String provider, Long integrationId) {
            this.contextLimit = contextLimit;
            this.loopLimit = loopLimit;
            this.outputTokenLimit = outputTokenLimit;
            this.runId = runId;
            this.owner = safeCorrelation(owner);
            this.repo = safeCorrelation(repo);
            this.pullRequestNumber = pullRequestNumber;
            this.provider = safeProvider(provider);
            this.integrationId = integrationId;
            new SecureRandom().nextBytes(fingerprintKey);
        }

        public void observeTurn(AgentRunContext.RoundObservation observation) {
            totalTurns++;
            mode = observation.mode();
            completionReason = observation.completionReason();
            outputTokens = observation.outputTokens();
            promptCharsEstimate = observation.promptCharacterEstimate();
            responseCharacters += observation.responseCharacters();
        }

        public void observeMode(ToolingMode resolvedMode) {
            mode = resolvedMode;
        }

        public void providerCallFailed() {
            providerCallFailed = true;
        }

        public void contextRoundExecuted() {
            contextRounds++;
        }

        public void addContextCharacters(int characters) {
            contextCharacters += Math.max(0, characters);
        }

        public void requestExecuted() {
            executedRequestOccurrences++;
        }

        public void requestSuppressed() {
            suppressedRequestOccurrences++;
        }

        public void observeNativeRequests(List<ToolCall> calls, String ref, int round) {
            for (ToolCall call : calls) {
                record(call.name(), canonical(call.args()), ref, round);
            }
        }

        public void observeLegacyRequests(List<ImplementationPlan.ToolRequest> requests,
                                          List<String> files, String ref, int round) {
            if (requests != null) {
                for (ImplementationPlan.ToolRequest request : requests) {
                    List<String> args = request.getArgs() == null ? List.of() : request.getArgs();
                    record(request.getTool(), canonicalStrings(args), ref, round);
                }
            }
            if (files != null) {
                for (String file : files) {
                    record("requestFiles", canonicalStrings(java.util.Collections.singletonList(file)), ref, round);
                }
            }
        }

        public Snapshot snapshot(Category category) {
            Integer effectiveContextLimit = mode == ToolingMode.NATIVE ? null : contextLimit;
            return new Snapshot(category, totalTurns, contextRounds, effectiveContextLimit, loopLimit,
                    outputTokenLimit, outputTokens, mode, completionReason, promptCharsEstimate,
                    responseCharacters, contextCharacters, requestOccurrences,
                    repeatedRequestOccurrences, repeatedRounds.size(), providerCallFailed,
                    runId, owner, repo, pullRequestNumber, provider, integrationId,
                    executedRequestOccurrences, suppressedRequestOccurrences);
        }

        private void record(String operation, String arguments, String ref, int round) {
            requestOccurrences++;
            String fingerprint = fingerprint(field(ref) + field(operation) + field(arguments));
            int previous = requestCounts.getOrDefault(fingerprint, 0);
            if (previous > 0) {
                repeatedRequestOccurrences++;
                repeatedRounds.add(round);
            }
            requestCounts.put(fingerprint, previous + 1);
        }

        private String fingerprint(String value) {
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(fingerprintKey, "HmacSHA256"));
                return java.util.HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("Unable to fingerprint a review request", e);
            }
        }

        private static String canonical(tools.jackson.databind.JsonNode node) {
            if (node == null || node.isNull()) {
                return "null";
            }
            if (node.isObject()) {
                List<String> names = new ArrayList<>(node.propertyNames());
                names.sort(Comparator.naturalOrder());
                StringBuilder result = new StringBuilder("object");
                for (String name : names) {
                    result.append(field(name)).append(field(canonical(node.get(name))));
                }
                return result.toString();
            }
            if (node.isArray()) {
                StringBuilder result = new StringBuilder("array");
                for (tools.jackson.databind.JsonNode child : node) {
                    result.append(field(canonical(child)));
                }
                return result.toString();
            }
            return (node.isString() ? "string" : "value")
                    + field(node.isString() ? node.asString() : node.toString());
        }

        private static String canonicalStrings(List<String> values) {
            StringBuilder result = new StringBuilder("array");
            for (String value : values) {
                result.append(field(value));
            }
            return result.toString();
        }

        private static String field(String value) {
            return value == null ? "-1:" : value.length() + ":" + value;
        }

        private static String safeCorrelation(String value) {
            if (value == null) {
                return null;
            }
            String safe = value.replaceAll("[^A-Za-z0-9_.-]", "_");
            return safe.length() <= 80 ? safe : safe.substring(0, 80);
        }

        private static String safeProvider(String value) {
            if (value == null || !value.matches("[A-Za-z0-9_-]{1,40}")) {
                return null;
            }
            return value.toLowerCase();
        }
    }

    private ReviewDiagnostics() {}
}
