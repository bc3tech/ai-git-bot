# PR Review

> Workflow key: **`review`** · Enabled by default on every bot.

## The problem it solves

Every pull request should get a careful review, but human reviewers are busy and
small PRs often merge without a second pair of eyes. **PR Review** guarantees
that every pull request gets an AI review with concrete, actionable feedback the
moment it opens or updates.

This is the built-in default workflow — it runs on every bot with no setup
beyond connecting the bot to your Git host and AI provider.

## What it does

- On PR open or update, it reads the diff and posts one review: a concise
  summary plus native inline comments on the file and line each finding is
  about (see [Inline review comments](#inline-review-comments)).
- It answers follow-up questions when you mention the bot (`@bot …`) in a PR
  comment.
- It responds to bot mentions inside inline (line-level) diff comments.
- It processes review submissions that mention the bot.
- It cleans up its per-PR state when the PR closes.
- Optionally, it applies a formal review action (approve / request changes) —
  see below.

## Settings

Set these on **System settings → Workflow configurations → Workflows → PR
Review**. The defaults work well; tune them only if your model has an unusually
small or large context window.

| Setting | Default | What it controls |
|---|---|---|
| `maxDiffCharsPerChunk` | `120000` | How large a diff can be before it is split into chunks for review. |
| `maxDiffChunks` | `8` | Maximum number of chunks reviewed for one PR. Extra chunks are skipped. |
| `retryTruncatedChunkChars` | `60000` | If a chunk is too big for the model, it is truncated to this size and retried once. |

Large PRs are automatically split into chunks and reviewed piece by piece, so a
big diff never silently fails.

## Inline review comments

The bot asks the model for its findings as structured data: a short summary plus
a list of findings, each optionally pointing at a file, a diff side (added or
removed line), and a single line number. The application, not the model, decides
where a finding can safely go:

- A finding whose file and line exist in the reviewed diff becomes a native
  inline comment on that line, on the added (new) or removed (old) side.
- Findings without a location, or with one that doesn't match the diff, are
  listed under **Other findings** in the summary. They are never pinned to a
  guessed line.
- The summary does not repeat findings that were posted inline.
- If the model doesn't return the structured format, its text is posted as an
  ordinary summary review, as before.

This is always on; there is no setting. Inline comments are anchored to the
exact commit that was reviewed. If the PR changes while the review is being
generated, or the provider can't produce a consistent snapshot, that review is
posted as an ordinary summary instead.

Provider support:

| Provider | Inline comments |
|---|---|
| Gitea | Yes — one review with all comments, read back to verify placement. |
| GitHub | Yes — one commit-bound review with all comments. |
| GitLab | Yes — summary note plus one diff discussion per finding. |
| Bitbucket | No — summary only, so a comment is never placed on the wrong line. |

When delivery partly fails (a comment is rejected, its placement can't be
confirmed, or it lands on an unexpected line), the bot posts a follow-up comment
with the affected findings so nothing is lost. If delivery can't be confirmed,
those findings may appear twice.

### Limitations

- **Gitea pending reviews:** if the bot account has its own unsubmitted
  (pending) review on the PR, Gitea would merge the new comments into it. The bot
  instead posts the full review as a regular comment, and skips the
  approve/request-changes decision, until the pending review is submitted or
  discarded. This check is best-effort and can race with a pending review
  created at the same moment.
- **Latest review wins:** a new review run for a PR cancels runs already in
  progress for that PR, including conversational review replies. When both
  workflows react to the same update, the agentic review takes precedence.
  Comments a cancelled run already posted remain on the PR. A queued run can
  still start after a newer one, and this coordination is per application
  instance only.
- The bot doesn't resolve, update, or delete inline comments from earlier
  reviews.

## Post-review action

After posting the review, the bot can optionally submit a formal review
decision, configured on the bot's Git integration:

| Value | Effect |
|---|---|
| `NONE` (default) | Only the review comments are posted. |
| `APPROVE` | The bot approves the PR. |
| `REQUEST_CHANGES` | The bot requests changes on the PR. |

## The review prompt

The review is driven by the operator-editable **Code-Review System-Prompt**
under **System settings → System prompts**. Edit it to change the review's tone,
focus, or policies; changes take effect on the next review. The structured
output format needed for inline comments is appended by the software, so the
prompt doesn't need to describe a response layout.

## Enabling / disabling

PR Review is enabled by default via the seeded `Default` configuration. To turn
it off, untick **PR Review** in the workflow configuration assigned to the bot
(or clone the configuration and remove it there).

## See also

- [PR Workflows overview](PR_WORKFLOWS.md)
- [Agentic PR Review](PR_WORKFLOWS_AGENTIC_REVIEW.md) — a deeper review that
  reads the surrounding code first.
