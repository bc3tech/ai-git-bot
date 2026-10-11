# Agentic PR Review

> Workflow key: **`agentic-review`** · Opt-in per bot.

## The problem it solves

A plain diff review sees only the changed lines. But the *right* review often
depends on code the diff doesn't show — the function being called, the test that
already covers this path, the convention used elsewhere in the module.
**Agentic PR Review** lets the bot explore the surrounding codebase before it
writes its review, so its feedback is grounded in how your project actually
works, not just the diff in isolation.

Use it instead of (or alongside) plain **PR Review** when you want deeper,
context-aware feedback and are willing to spend more time and tokens per review.

## What it does

- Reads the pull request and looks around the repository as needed before
  forming an opinion.
- Focuses on the files that matter first, so it handles large PRs without
  drowning in noise.
- Posts one review: a concise summary plus native inline comments on the file
  and line each finding is about. Placement, provider support, and limitations
  are the same as for [PR Review](PR_WORKFLOWS_REVIEW.md#inline-review-comments).
- Optionally submits a formal review decision (approve / request changes) — see
  below.

The bot is **read-only**: this workflow never commits, pushes, creates branches,
or changes your code. Its only outputs are the review (summary and inline
comments) and, when you enable it, the formal review decision.

Review runs share a per-PR database lease with plain PR Review. A waiting run
acquires the lease before reading the latest snapshot; heartbeat renewal keeps
it held throughout exploration, grounding, publication, and revision-state
persistence. Cancellation and lease loss prevent further writes. When both
workflows react to the same update, agentic review takes precedence.

The workspace uses a detached checkout of the snapshot's head SHA, and API file
reads use that same SHA. A failed pinned checkout or read stops the review; the
bot never substitutes a branch's newer content. If no head SHA is available,
exploration is limited to the supplied diff, with summary-only publication.

## Failure diagnostics

If the run fails before producing a complete review, the run summary records
the failure category and known limiting counts. Categories distinguish
`context-round-limit`, `loop-round-limit`, `output-token-limit`,
`incomplete-completion`, `unknown-completion`, `empty-feedback`,
`setup-failure`, `provider-request-failure`, `publication-failure`, and
`unknown-execution-failure`.

The detailed workflow step records available `turns`, `contextRounds`,
`loopRounds`, `outputTokenLimit`, `outputTokens`, `mode`, normalized
`completion`, `promptCharsEstimate`, `responseChars`, `contextChars`, and
provider/integration correlation. `requestOccurrences` counts context
operations; `repeatedRequestOccurrences` counts duplicates, while
`repeatedRequestRounds` counts turns containing at least one duplicate. The step
also records `providerCallFailed` and whether the single plain PR failure notice
was delivered, failed, or unavailable, including a controlled reason when it
could not be attempted. Unknown metadata is shown as unavailable.

Duplicate identities include the pinned repository ref. Native calls are
observed in execution order, and legacy requests use the same reviewed
revision. Branch-switch requests pass through the read-only review policy and
cannot change either the workspace revision or the diagnostic identity.

Context-exploration rounds and total model turns are separate limits. A
provider response ending with `END_TURN` does not by itself mean the review
finished: the agent may still request context. Repeated requests are an
observation to help investigate a run, not proof of cause. A failure notice
does not contain partial findings or make a formal review decision. Successful
reviews do not receive a failure notice, and no-diff runs remain skipped.

Added diagnostics contain bounded metadata only; they do not include prompts,
model output, source contents, or tool arguments.

## Settings

Set these on **System settings → Workflow configurations → Workflows → Agentic
PR Review**.

| Setting | Type | Default | What it controls |
|---|---|---|---|
| `maxToolRounds` | integer | `12` | How much exploring the bot may do (1–30). Higher means deeper analysis at higher cost. |
| `enableFormalReviewDecision` | boolean | `false` | Let the bot approve or request changes, not just comment. |
| `formalReviewDecisionPrompt` | text | *(built-in default)* | Criteria the model uses to classify findings by severity. Only used when the decision is enabled. |
| `blockerThreshold` | integer | *(empty)* | Maximum allowed `BLOCKER` findings for a formal `APPROVE`. Empty means ignored. |
| `mediumThreshold` | integer | *(empty)* | Maximum allowed `MEDIUM` findings for a formal `APPROVE`. Empty means ignored. |
| `lowThreshold` | integer | *(empty)* | Maximum allowed `LOW` findings for a formal `APPROVE`. Empty means ignored. |
| `doubleCheckEnabled` | boolean | `true` | Select new findings for independent AI verification. |
| `doubleCheckMinSeverity` | enum | `LOW` | Check new findings at or above `LOW`, `MEDIUM`, `HIGH`, or `BLOCKER`. |
| `doubleCheckAiIntegrationId` | integer | `0` | Optional second AI integration ID; blank or `0` uses the review integration. |

Each finding needs a verbatim code quote or review-wide citations. The bot
verifies this evidence against the pinned revision before the AI double-check,
then drops unsupported findings and suppresses unchanged duplicates. Disputed or
context-changed prior findings are checked regardless of enablement or severity.
An unknown integration ID falls back to the review integration with an audit warning.

### Formal review decision

When `enableFormalReviewDecision` is on, the model classifies each finding as
`BLOCKER`, `MEDIUM`, or `LOW`. After grounding, the application counts only
surviving findings and compares those counts to the configured thresholds.
Trailing model-provided aggregate counts cannot drive the decision:

- If **all configured thresholds** are satisfied, the bot submits `APPROVE`.
- If **any configured threshold** is exceeded, the bot submits `REQUEST_CHANGES`.
- If **no thresholds** are configured, the bot leaves the review state unchanged
  (`NONE`) while still posting the review text.
- An unset threshold is ignored entirely, so you can enforce only the severities
  that matter to you (for example, only `BLOCKER`).

The decision is submitted together with the inline review, so the approval or
change request and its comments arrive as one review where the provider allows
it. A degraded review never approves: held findings, unavailable double-checks,
unpinned evidence, or unparseable output leave the decision at `NONE` unless
surviving findings independently require changes. Unparseable output produces
only a short failure summary, never the model's unverified review text.

Previous findings and human replies are supplied as labeled history, not current
code. Grounded re-reviews may reply to disputes or withdraw obsolete findings,
using linked PR comments where native thread operations are unavailable. Failed
thread actions leave the revision degraded for retry. An explicit request skips
only a pinned, unchanged, non-degraded revision with no new non-bot comments.
The bot replies with the reviewed SHA and clears its eyes acknowledgement.

`review-state.lease-ttl` defaults to `10m`; `review-state.lease-wait-max` defaults
to `30m`. Waiting occurs in the async workflow worker. A timeout posts a failure
comment and clears the acknowledgement. The double-check shares the application's
`review.chunking.max-diff-chars-per-chunk` and `review.chunking.max-diff-chunks`
budgets.

## The review prompt

The bot's role description is the operator-editable **Review-Agent
System-Prompt** under **System settings → System prompts**. Edit it to steer the
review; the mechanics of *how* the bot explores are handled by the software and
are not part of this prompt, so editing it can't break anything.

## Enabling it

1. Open **System settings → Workflow configurations → Workflows** for the
   relevant configuration.
2. Tick **Agentic PR Review** and adjust its settings if you like.
3. To let the bot approve / request changes, tick **Enable formal review
   decision**, optionally customise the decision prompt, and set at least one
   severity threshold (for example, **Blocker must be less or equal = 0**).

It only runs for bots that have it explicitly enabled — it never replaces or
duplicates the default **PR Review** workflow.

## See also

- [PR Workflows overview](PR_WORKFLOWS.md)
- [PR Review](PR_WORKFLOWS_REVIEW.md) — the simpler, always-on review.
