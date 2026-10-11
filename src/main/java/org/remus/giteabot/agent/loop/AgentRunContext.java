package org.remus.giteabot.agent.loop;

import lombok.Setter;
import org.remus.giteabot.agent.session.AgentSession;
import org.remus.giteabot.prworkflow.agentreview.DiffSummary;
import org.remus.giteabot.ai.StopReason;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Mutable, per-run context shared between {@link AgentLoop} and
 * {@link AgentStrategy} implementations.
 */
public final class AgentRunContext {

    private final AgentSession session;
    private final String owner;
    private final String repo;
    private final Long issueNumber;
    private final Path workspaceDir;
    @Setter
    private String baseBranch;
    @Setter
    private ToolingMode toolingMode;
    @Setter
    private DiffSummary diffSummary;
    @Setter
    private Consumer<ToolCallRecord> auditToolCallConsumer;
    @Setter
    private Consumer<RoundObservation> roundObserver;
    @Setter
    private Consumer<ToolingMode> modeObserver;
    @Setter
    private Runnable providerCallFailureObserver;

    public AgentRunContext(AgentSession session, String owner, String repo,
                           Long issueNumber, Path workspaceDir, String baseBranch) {
        this.session = session;
        this.owner = owner;
        this.repo = repo;
        this.issueNumber = issueNumber;
        this.workspaceDir = workspaceDir;
        this.baseBranch = baseBranch;
    }

    public AgentSession session() { return session; }
    public String owner() { return owner; }
    public String repo() { return repo; }
    public Long issueNumber() { return issueNumber; }
    public Path workspaceDir() { return workspaceDir; }
    public String baseBranch() { return baseBranch; }
    public ToolingMode toolingMode() { return toolingMode; }
    public DiffSummary diffSummary() { return diffSummary; }
    public Consumer<ToolCallRecord> auditToolCallConsumer() { return auditToolCallConsumer; }
    public Consumer<RoundObservation> roundObserver() { return roundObserver; }
    public Consumer<ToolingMode> modeObserver() { return modeObserver; }
    public Runnable providerCallFailureObserver() { return providerCallFailureObserver; }

    /** Metadata-only observation; never includes prompts, responses, or tool arguments. */
    public record RoundObservation(
            int turn,
            ToolingMode mode,
            StopReason completionReason,
            Long outputTokens,
            int outputTokenLimit,
            int promptCharacterEstimate,
            int responseCharacters
    ) {}

    public record ToolCallRecord(
            String toolName,
            String arguments,
            String resultExcerpt,
            boolean success,
            long durationMs,
            Long inputTokens,
            Long outputTokens,
            int round
    ) {}
}
