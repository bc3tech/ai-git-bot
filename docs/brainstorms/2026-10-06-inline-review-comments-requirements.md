---
date: 2026-10-06
topic: inline-review-comments
---

# File and Line-Based AI Reviews

## Problem Frame

Generated AI reviews currently publish findings as one Markdown review body.
Text such as `[Comment on packages/mcp-server/src/retry.ts]:` describes a
location but does not create a comment there. Reviewers must find the relevant
code themselves and cannot discuss the finding in a native inline thread.

The goal is to publish actionable findings at their actual file and line
locations using each repository provider's native review capabilities, while
preserving findings that cannot safely be placed inline.

## Requirements

**Coverage and Review Presentation**

- R1. Apply the feature to both standard reviews (AI evaluates the supplied diff
  and gathered context) and agentic reviews (AI explores repository context
  before completing its review).
- R2. Support every currently implemented provider: Gitea, GitHub, GitLab, and
  Bitbucket Cloud, using each provider's appropriate native APIs.
- R3. Always publish findings inline when a valid, supported location exists.
  Do not introduce an opt-in or summary-only setting.
- R4. Publish a short overall summary and retain existing formal review decision
  behavior where enabled and supported. Detailed findings successfully posted
  inline must not also be repeated in the summary.
- R5. Preserve findings without a valid or supported inline location in the
  summary, including available file/line references. Do not add an explanation
  of why these ordinary findings were not placed inline. Review-wide findings
  belong in the summary as well.

**Correct Placement**

- R6. Each inline finding must contain meaningful comment text and identify an
  actual file, line, and diff side in the revision reviewed. Validate placement
  against that diff; never guess or move a finding to an unrelated line.
- R7. Support both new-side added/changed lines and old-side deleted lines where
  the provider permits them. Preserve findings whose requested locations are
  unsupported in the summary under R5.
- R8. A PR update during review must not cause a finding to be attached to the
  same line number in different code. If placement in the reviewed revision
  cannot be established safely, retain the finding in the summary rather than
  attach it to an unverified current location. This includes Bitbucket Cloud:
  use summary fallback where its documented API cannot guarantee safe placement,
  rather than accept a race between checking the PR and posting a comment.
  For the user's Gitea 28.0.0 instance, permit inline publication after verifying
  that the PR still matches the reviewed version, with post-write verification
  and explicit reporting of detected mismatches. The user accepts Gitea's
  remaining concurrent-push race; this exception does not apply to Bitbucket.

**Publication Reliability and Compatibility**

- R9. If inline publication fails or succeeds only partially, preserve
  unpublished findings in a summary comment and explicitly report the
  publication failure. Do not repeat findings known to have been posted
  successfully. An unconfirmed outcome must not be reported as success.
- R10. Keep existing review triggers, exclusions, severity classification,
  approval/change-request rules, and follow-up interactions intact. Inline
  placement must not change a finding's severity or whether it contributes to
  a configured formal decision. Keep submitting configured formal decisions
  under existing behavior even if new commits arrive; do not introduce a new
  stale-revision decision gate as part of this feature. The latest-wins and
  same-update precedence rules below intentionally change execution selection,
  not trigger eligibility, severity, or approval policy.
- R11. Do not introduce thread reconciliation, automatic resolution, or
  replacement of historical review comments. When a run is superseded, retain
  comments already posted or committed by an already-sent provider request.
- R12. A new review request for the same bot and PR supersedes any in-progress
  standard or agentic review. Request termination of its work promptly and
  prevent further old-run publication calls, including summary, fallback,
  formal decision, and error comments. Discard late AI results. Start the
  newest review without waiting for cancelled generation to finish. A provider
  request already in flight may finish; do not initiate additional old-run
  writes or delete its published comments.
- R13. When the same PR update would trigger both enabled review workflows,
  select agentic review. Do not let background scheduling order choose the
  winner. A later explicit request of either type can supersede it.

## Success Criteria

- Both review workflows produce native inline comments on all four providers
  for findings with valid supported locations, rather than textual
  `[Comment on ...]` stand-ins.
- Added/changed-line and deleted-line findings attach to the correct file,
  revision, and side wherever supported by the provider.
- Invalid, missing, unsupported, and detected unsafe stale locations produce
  summary findings without fabricated anchors or routine placement explanations.
  Gitea's explicitly accepted concurrent-push race is checked and any detected
  publication mismatch is reported as a publishing failure.
- Reviews contain a concise summary, with no duplication of successfully
  published inline detail, and preserve the configured formal decision.
- Rejected comments and partial publication retain unpublished feedback and
  expose publication errors instead of silently losing findings.
- Reviews without findings still produce a concise overall assessment.
- A superseded run cannot publish a late AI result, new fallback/error comment,
  or formal decision; only the newest eligible run continues publishing.
- Same-update dual enablement deterministically selects agentic review.

## Scope Boundaries

- No application implementation in this brainstorm.
- No new repository providers or Bitbucket Server/Data Center support.
- No new review-quality, severity, or approval policies.
- No automatic fixes, suggested-change application, or file mutations.
- No retroactive conversion of existing Markdown reviews.
- No cross-run deduplication, thread resolution, or deletion of superseded
  comments. Latest-wins cancellation is in scope; historical reconciliation is not.
- Single-line anchors are sufficient for this release; multi-line ranges and
  native file-only comments are not required.

## Key Decisions

- Extend the existing provider integration rather than add a separate
  Gitea-specific adapter. Provider-neutral inline publication already exists.
- Separate review generation from publication. The application validates and
  publishes findings; the model does not independently post review comments.
- Use inline comments unconditionally when safe, with summary fallback for
  findings that cannot be anchored.
- Distinguish ordinary unplaceable findings from publication failures: only
  publication failures need an explicit operational explanation.
- Preserve existing decision semantics independently of presentation.
- Strict location safety takes precedence over inline coverage on Bitbucket
  Cloud when its API cannot bind a comment to the reviewed version.
- Formal decisions retain existing behavior after a concurrent push. The user
  explicitly chose not to add a stale-revision gate for approvals or requests
  for changes; this exception does not relax inline location safety.
- Gitea target version is exactly 28.0.0, as confirmed by the user. After source
  research revealed mixed historical/current-head comment construction, the
  user approved Gitea inline publication with unchanged-PR checks and reporting
  of detected mismatches, rather than the strict fallback chosen for Bitbucket.
- Newer reviews supersede older work across both review types, rather than
  merely waiting for old publication to finish. Agentic takes precedence for
  same-update dual enablement. Already-posted output remains in place.

## Verified Repository Context

- `src/main/java/org/remus/giteabot/repository/RepositoryApiClient.java`
  declares provider-neutral inline comment publication.
- `src/main/java/org/remus/giteabot/review/CodeReviewService.java` publishes
  generated reviews as one body; its inline calls serve incoming-comment
  follow-ups.
- `src/main/java/org/remus/giteabot/prworkflow/agentreview/AgentReviewService.java`
  publishes the final agent response as one review body. Optional structured
  findings currently feed event hooks rather than inline publication.
- The Gitea, GitHub, GitLab, and Bitbucket API clients implement inline
  publication. Existing helpers accept a file and new-side line; old-side
  coverage and reliability require investigation during planning.
- `src/main/java/org/remus/giteabot/prworkflow/PrWorkflowRunService.java`
  already cancels previous runs by workflow key. Cross-review-type supersession
  and generation/publication cancellation checks need to be strengthened.

## Outstanding Questions

### Deferred to Planning

- [Affects R1, R6][Technical] Define a reliable finding output contract for both
  review workflows, including non-native-tool models and malformed model output.
  Preserve meaningful feedback when output cannot be interpreted safely.
- [Affects R2, R7][Needs research] Verify provider/version-specific location
  semantics for deleted lines, renames, reviewed revisions, and diff context.
  Establish supported cases with provider contract tests.
- [Affects R2, R4, R9][Technical] Determine which providers can publish a summary,
  decision, and multiple comments together, and how to track partial success or
  ambiguous network outcomes without claiming success or blindly duplicating
  confirmed comments.
- [Affects R8][Technical] Determine how each provider binds inline comments to
  the reviewed revision and detects an intervening PR update.
- [Affects R12, R13][Technical] Extend existing run cancellation across review
  types, establish request-order admission, and stop generation/retries and
  further publication without treating intentional supersession as an error.
- [Affects R9][Technical] Audit adapter failure signaling. In particular, the
  existing GitLab inline helper returns without throwing when MR retrieval
  yields no result; callers need reliable publication outcomes.
- [Affects R10][Technical] Integrate the new output requirements with existing
  operator-edited prompts, chunked reviews, severity output, and event hooks.

## Next Steps

Proceed to `/ce-plan` for structured implementation planning.
