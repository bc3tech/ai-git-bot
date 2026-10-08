# Issue-Assigned Workflows

> What a bot does when it is assigned to an issue — and on follow-up issue
> comments — is a **per-bot configuration choice**, not a hardcoded bot type.

An **issue-assigned workflow** is the issue-side counterpart of a
[PR workflow](PR_WORKFLOWS.md). Where PR workflows answer "what should happen
on pull-request events?", issue-assigned workflows answer "what should happen
when this bot is assigned to an issue?". Both are configured through the same
named **workflow configurations** in System settings — discriminated by kind
(`PR` vs. `ISSUE`) — and both are selectable per bot, independently of each
other.

This mechanism replaced the deprecated **Bot Type** (coding/writer)
categorization. There is no bot-type selector anymore; adding a new issue
behavior no longer means adding a new hardcoded bot category.

---

## The built-in workflows

| Key | Display name | What it does | Replaces |
|---|---|---|---|
| `issue-coding` | Coding Agent | Implements the assigned issue (workspace, branch, validation, pull request) and continues on follow-up comments. | former **Coding bot** |
| `issue-writer` | Writer Agent | Refines the assigned issue into a structured, testable work item and answers follow-up questions in comments. | former **Writer bot** |
| `issue-triage` | Issue Triage | Routes a new or newly assigned issue to the right assignee (person, bot, or `none`) using an editable prompt, posts the routing reason as a comment, and performs the assignment. Never implements the issue. | — |

The coding and writer workflows are seeded by Flyway migration `V39` as two
ready-made configurations: **Issue: Coding Agent** (the ISSUE-kind default)
and **Issue: Writer Agent**; `V42` adds the ready-made, non-default
**Issue: Triage** configuration. See [Coding Agent](CODING_AGENT.md),
[Writer Agent](WRITER_AGENT.md), and [Issue Triage](ISSUE_TRIAGE.md) for the
behavior details.

## Configuring a bot

The bot edit form has two independent selectors:

- **PR Workflow Configuration** — which workflows run on pull-request events.
- **Issue-Assigned Workflow Configuration** — which workflow runs when the
  bot is assigned to an issue (and how follow-up issue comments are handled).

Leaving a selector empty inherits the kind's default configuration. To keep a
bot completely silent on PRs (the old writer-bot behavior), assign the seeded
empty **No PR workflows** configuration as its PR workflow configuration.

Issue-assigned workflow configurations are managed under
**System settings → Issue-assigned workflow configurations** (list / create /
clone / edit / select workflows), mirroring the PR section. Usually exactly
one issue workflow is enabled per configuration; enabling several runs them
sequentially in stable order.

## Adding a new issue workflow

New issue behaviors are ordinary Spring beans implementing the
`org.remus.giteabot.issueworkflow.IssueWorkflow` SPI:

```java
@Component
public class ChoreRouterIssueWorkflow implements IssueWorkflow {
    public String key() { return "chore-router"; }          // lowercase kebab-case, unique
    public String displayName() { return "Chore Router"; }
    public void onIssueAssigned(IssueWorkflowContext context) { /* ... */ }
    public void onIssueComment(IssueWorkflowContext context) { /* ... */ }
}
```

- The bean is discovered automatically via `IssueWorkflowRegistry` (duplicate
  or malformed keys fail startup).
- `IssueWorkflowContext` carries the bot, the raw webhook payload, and the
  workflow's persisted parameters (declare them via `paramsSchema()`, like PR
  workflows).
- The `IssueWorkflowOrchestrator` resolves the enabled workflow(s) from the
  bot's issue-assigned configuration and owns the lifecycle:
  `issueassignment.started/completed/failed` outgoing events and bot error
  records for assignments. When at least one workflow is enabled, the bot adds
  a 👀 reaction to the assigned issue before running it. Follow-up comments
  receive a 👀 acknowledgment reaction on the triggering comment plus error
  records on failure.
- To ship a new workflow enabled on the default configuration, add a
  follow-up Flyway migration (H2 + PostgreSQL) following the `V29`/`V37`
  precedent — the application never auto-extends seeded configurations at
  runtime.

Routing-style flows (e.g. a *chore router* that classifies an issue and
hands it to the right specialist) are designed to be built on this SPI.
