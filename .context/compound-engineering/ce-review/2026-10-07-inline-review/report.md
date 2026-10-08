# ce-review (autofix) — inline review comments

Scope: a0dd4a8..HEAD (excluding docs/). Plan: docs/plans/2026-10-06-001-feat-inline-review-comments-plan.md (explicit).
Reviewers: correctness, testing, maintainability, project-standards, reliability, api-contract, security, adversarial.
Verdict: Ready with fixes. All plan units (0-8) are implemented; the gated items below are follow-up hardening.

## Applied safe_auto fixes
- ReviewDiffPositionParser.unquote: validate all three octal digits; otherwise keep the escape literally (malformed escapes previously threw NumberFormatException and aborted publication). (security)
- ReviewDiffPositionParser.parse: a trailing empty element while hunk lines are still owed marks the file malformed instead of inventing a context line. Blank context lines in the middle of a hunk are still accepted. (correctness, adversarial)
- ReviewOutputParser.parse: a present findings field that is not an array now falls back to publishing the raw text with a warning. (correctness, adversarial)
- Regression tests were added for each fix; the full suite passes.

## Residual actionable work (todos)
| # | Sev | File | Issue | Reviewers |
|---|-----|------|-------|-----------|
| 001 | P1 | GitLabApiClient.java ~154-196 | Fence is not re-checked between the discussion, summary, and action writes, so a superseded run keeps posting | reliability, adversarial |
| 002 | P1 | GitLabApiClient.java hasFirstNotePosition | Any position map (even an empty one) is treated as CONFIRMED; path and line are not compared | api-contract, correctness |
| 003 | P1 | ReviewPublicationService.java ~91 | The summary-only path (no anchors) bypasses the Gitea pending-review guard, so the action can submit an existing pending review | correctness |
| 004 | P2 | GitLabApiClient.java ~186-196 | Summary and action delivery are combined, so an action failure reposts a summary that was already delivered | reliability, adversarial |
| 005 | P2 | AgentReviewService.java ~281 | The fallback can repost the full review after a partial or ambiguous publication | reliability |
| 006 | P2 | ReviewPublicationService.java | No cap on the number of findings (one GitLab POST and notification per finding) | security |
| 007 | P2 | ReviewPublicationService.java inlineBody | GitLab quick actions and @mentions in inline bodies are not neutralized | security |
| 008 | P2 | CodeReviewService.java ~162 | The two session writes are not atomic with respect to supersession | adversarial |
| 009 | P3 | ReviewDocument.java | The `structured` flag is unused in production | maintainability |

## Advisory
- A Gitea pending review created concurrently between the preflight check and the POST can absorb comments while they are reported as published (adversarial, P2). This is documented as a known limitation.
- GitHub has no per-comment read-back, so a successful POST is treated as confirmation.
- Testing gaps: GitLab NEW-only and OLD-only payloads; GitHub fallback for an unstable snapshot; failure of the supplemental comment; CodeReviewService multi-chunk merge; supersession tests using a barrier.
