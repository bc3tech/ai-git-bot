package org.remus.giteabot.review.grounding;

import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.model.CommentScan;
import org.remus.giteabot.repository.model.ReviewComment;
import org.remus.giteabot.review.grounding.PriorFindingsDigest.CommentCursor;
import org.remus.giteabot.review.grounding.PriorFindingsDigest.PriorFinding;
import org.remus.giteabot.review.grounding.PriorFindingsDigest.Reply;
import org.remus.giteabot.review.state.ReviewFindingRecord;
import org.remus.giteabot.review.state.ReviewRunLeaseService.LeaseToken;
import org.remus.giteabot.review.state.ReviewStateService;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

@Service
public class PriorFindingsService {

    private static final Comparator<Reply> COMMENT_ORDER = Comparator
            .comparing(Reply::createdAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Reply::id, Comparator.nullsLast(Comparator.naturalOrder()));

    private final ReviewStateService stateService;

    public PriorFindingsService(ReviewStateService stateService) {
        this.stateService = stateService;
    }

    /** Reconciliation needs complete provider scans, not pinned source; safe for unpinned runs too. */
    public PriorFindingsDigest load(RepositoryApiClient api, LeaseToken lease, String workflowKey, String botLogin) {
        if (botLogin == null || botLogin.isBlank()) {
            throw new IllegalArgumentException("Bot login is required to identify owned comments");
        }
        CommentScan<ReviewComment> threads = scan(() -> api.listReviewThreadComments(
                lease.owner(), lease.repo(), lease.prNumber()));
        CommentScan<Map<String, Object>> comments = scan(() -> api.listPullRequestComments(
                lease.owner(), lease.repo(), lease.prNumber()));
        boolean complete = threads.complete() && comments.complete();
        List<ProviderComment> all = new ArrayList<>();
        for (ReviewComment comment : threads.comments()) {
            all.add(new ProviderComment(new Reply(string(comment.getId()), comment.getUserLogin(),
                    nonNull(comment.getBody()), instant(comment.getCreatedAt())), comment.getThreadId(),
                    comment.getParentId(), true));
        }
        for (Map<String, Object> comment : comments.comments()) {
            all.add(fromMap(comment));
        }
        if (complete) {
            reconcile(all, lease, workflowKey, botLogin);
        }
        List<ReviewFindingRecord> active = stateService.activeFindings(lease.botId(), lease.owner(), lease.repo(),
                lease.prNumber(), workflowKey);
        Map<ReviewFindingRecord, List<Reply>> associated = new LinkedHashMap<>();
        active.forEach(finding -> associated.put(finding, new ArrayList<>()));
        List<Reply> nonBot = new ArrayList<>();
        if (complete) {
            Map<ReviewFindingRecord, String> links = new LinkedHashMap<>();
            for (ReviewFindingRecord finding : active) {
                if (finding.getRemoteCommentId() != null) {
                    links.put(finding, api.pullRequestCommentLink(lease.owner(), lease.repo(), lease.prNumber(),
                            finding.getRemoteCommentId()).orElse(null));
                }
            }
            Set<String> seen = new LinkedHashSet<>();
            for (ProviderComment comment : all) {
                Reply reply = comment.reply();
                if (isBot(reply, botLogin) || reply.body().isBlank()) {
                    continue;
                }
                // Providers include inline comments in both scans; thread metadata takes precedence.
                String key = reply.id() == null ? reply.login() + "\n" + reply.body() : reply.id();
                if (!seen.add(key)) {
                    continue;
                }
                nonBot.add(reply);
                List<ReviewFindingRecord> matches = active.stream().filter(finding -> inThread(comment, finding)).toList();
                if (matches.isEmpty()) {
                    matches = active.stream().filter(finding -> contains(reply.body(), finding.getMarker())
                            || containsLink(reply.body(), links.get(finding))).toList();
                }
                if (matches.isEmpty() && !comment.threadComment()) {
                    matches = quoteMatches(reply.body(), active);
                }
                for (ReviewFindingRecord finding : matches) {
                    associated.get(finding).add(reply);
                }
            }
        }
        List<PriorFinding> priors = new ArrayList<>();
        associated.forEach((finding, replies) -> {
            replies.sort(COMMENT_ORDER);
            String answeredId = finding.getLastAnsweredCommentId();
            Reply answered = replies.stream().filter(reply -> answeredId != null && answeredId.equals(reply.id()))
                    .findFirst().orElse(null);
            boolean hasNew = replies.stream().anyMatch(reply -> (reply.id() == null || !Objects.equals(answeredId, reply.id()))
                    && (answered == null || new CommentCursor(answered.createdAt(), answered.id()).isNew(reply)));
            priors.add(new PriorFinding(finding, replies, hasNew,
                    replies.isEmpty() ? null : replies.getLast().id()));
        });
        // Missing timestamps must remain new; they cannot advance a durable timestamp cursor.
        Reply latest = nonBot.stream().filter(reply -> reply.createdAt() != null).max(COMMENT_ORDER).orElse(null);
        return new PriorFindingsDigest(priors, complete,
                latest == null ? null : new CommentCursor(latest.createdAt(), latest.id()));
    }

    /** Missing identities, timestamps, or incomplete scans cannot prove comment absence. */
    public static boolean hasNewComments(RepositoryApiClient api, LeaseToken lease,
                                         String botLogin, CommentCursor cursor) {
        if (botLogin == null || botLogin.isBlank()) {
            return true;
        }
        try {
            CommentScan<ReviewComment> threads = api.listReviewThreadComments(lease.owner(), lease.repo(), lease.prNumber());
            CommentScan<Map<String, Object>> comments = api.listPullRequestComments(lease.owner(), lease.repo(), lease.prNumber());
            if (threads == null || comments == null || !threads.complete() || !comments.complete()) {
                return true;
            }
            List<Reply> replies = new ArrayList<>();
            for (ReviewComment comment : threads.comments()) {
                replies.add(new Reply(string(comment.getId()), comment.getUserLogin(), nonNull(comment.getBody()),
                        instant(comment.getCreatedAt())));
            }
            comments.comments().forEach(comment -> replies.add(fromMap(comment).reply()));
            return replies.stream().anyMatch(reply -> !isBot(reply, botLogin)
                    && (reply.login() == null || cursor.isNew(reply)));
        } catch (RuntimeException e) {
            return true;
        }
    }

    private void reconcile(List<ProviderComment> comments, LeaseToken lease, String workflowKey, String botLogin) {
        List<ProviderComment> own = comments.stream().filter(comment -> isBot(comment.reply(), botLogin)).toList();
        for (ReviewFindingRecord pending : stateService.pendingFindings(lease.botId(), lease.owner(), lease.repo(),
                lease.prNumber(), workflowKey)) {
            ProviderComment found = own.stream().filter(comment -> contains(comment.reply().body(), pending.getMarker()))
                    .findFirst().orElse(null);
            if (found == null) {
                stateService.discardPending(pending.getId(), lease);
            } else {
                stateService.markPublished(pending.getId(), found.reply().id(),
                        pending.isSummaryOnly() ? null : found.threadId(), lease);
            }
        }
        for (ReviewFindingRecord finding : stateService.activeFindings(lease.botId(), lease.owner(), lease.repo(),
                lease.prNumber(), workflowKey)) {
            boolean exists = own.stream().anyMatch(comment -> finding.getRemoteCommentId() != null
                    ? finding.getRemoteCommentId().equals(comment.reply().id())
                    : contains(comment.reply().body(), finding.getMarker()));
            if (!exists) {
                stateService.markGone(finding.getId(), lease);
            }
        }
    }

    private static boolean inThread(ProviderComment comment, ReviewFindingRecord finding) {
        if (!comment.threadComment() || finding.isSummaryOnly()) {
            return false;
        }
        return finding.getRemoteThreadId() != null && finding.getRemoteThreadId().equals(comment.threadId())
                || finding.getRemoteCommentId() != null && finding.getRemoteCommentId().equals(comment.parentId());
    }

    private static List<ReviewFindingRecord> quoteMatches(String body, List<ReviewFindingRecord> active) {
        Set<String> quotedLines = normalizedLines(body);
        Set<ReviewFindingRecord> matches = new LinkedHashSet<>();
        for (String line : quotedLines) {
            if (line.isBlank()) {
                continue;
            }
            List<ReviewFindingRecord> owners = active.stream()
                    .filter(finding -> normalizedLines(nonNull(finding.getQuote())).contains(line)).toList();
            if (owners.size() == 1) {
                matches.add(owners.getFirst());
            }
        }
        return List.copyOf(matches);
    }

    private static Set<String> normalizedLines(String text) {
        Set<String> lines = new LinkedHashSet<>();
        for (String line : text.split("\\R")) {
            String normalized = line.strip();
            if (normalized.startsWith(">")) {
                normalized = normalized.substring(1).strip();
            }
            if (normalized.startsWith("`") && normalized.endsWith("`") && normalized.length() > 1) {
                normalized = normalized.substring(1, normalized.length() - 1).strip();
            }
            lines.add(normalized);
        }
        return lines;
    }

    private static ProviderComment fromMap(Map<String, Object> map) {
        String body = string(map.get("body"));
        if (body == null) {
            body = nested(map.get("content"), "raw");
        }
        String login = nested(map.get("user"), "login", "nickname", "display_name");
        if (login == null) {
            login = nested(map.get("author"), "username", "login");
        }
        String timestamp = string(map.get("created_at"));
        if (timestamp == null) timestamp = string(map.get("created_on"));
        if (timestamp == null) timestamp = string(map.get("submitted_at"));
        return new ProviderComment(new Reply(string(map.get("id")), login, nonNull(body), instant(timestamp)),
                null, null, false);
    }

    private static String nested(Object object, String... keys) {
        if (object instanceof Map<?, ?> map) {
            for (String key : keys) {
                if (map.get(key) != null) return string(map.get(key));
            }
        }
        return null;
    }

    private static Instant instant(String timestamp) {
        try {
            return timestamp == null ? null : Instant.parse(timestamp);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static <T> CommentScan<T> scan(Supplier<CommentScan<T>> operation) {
        try {
            CommentScan<T> result = operation.get();
            return result == null ? new CommentScan<>(List.of(), false) : result;
        } catch (RestClientException | UnsupportedOperationException e) {
            return new CommentScan<>(List.of(), false);
        }
    }

    private static boolean isBot(Reply reply, String botLogin) {
        return botLogin != null && botLogin.equalsIgnoreCase(reply.login());
    }

    private static boolean contains(String text, String token) {
        return token != null && !token.isBlank() && text.contains(token);
    }

    private static boolean containsLink(String text, String link) {
        return link != null && !link.isBlank()
                && Pattern.compile(Pattern.quote(link) + "(?=$|[\\s)\\]>\"',;.!])").matcher(text).find();
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }

    private record ProviderComment(Reply reply, String threadId, String parentId, boolean threadComment) {}
}
