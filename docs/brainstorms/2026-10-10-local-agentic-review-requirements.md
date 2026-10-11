---
date: 2026-10-10
topic: local-agentic-review
---

# Agentic Review Failure Diagnostics

## Problem Frame

An operator using llama.cpp sees Agentic Review run through multiple model
turns, finish as FAILED, and leave no feedback on the PR. The first improvement
is to explain failures reliably, not change what constitutes a completed review
or assume that the local model is incapable.

Read-only inspection of deployed bot logs confirmed a run stopped at its
configured 10 legacy context rounds on turn 11, despite a 20-turn overall
budget. All observed model turns reported END_TURN; they continued requesting
context rather than producing a final review. The deployed run recorded
REVIEW_FAILED. This was not an observed timeout or output-token truncation.

Rounds 3 through 11 supplied similarly sized context and produced very short
replies while the full prompt grew. Repeated requests are a hypothesis, not a
confirmed cause: the inspected logs did not expose their contents.

The deployed llama.cpp client logged /v1/completions, while this checkout uses
/completion. Findings about this checkout must not be represented as verified
behavior of the deployed image.

Relevant source:
- `src/main/java/org/remus/giteabot/prworkflow/agentreview/ReviewAgentStrategy.java`
  distinguishes incomplete turns, empty feedback, and budget failures, but
  reuses an overall-budget message for the legacy context cap.
- `src/main/java/org/remus/giteabot/prworkflow/agentreview/AgentReviewService.java`
  returns FAILED without publishing a failed loop outcome.
- `src/main/java/org/remus/giteabot/prworkflow/agentreview/AgentReviewWorkflow.java`
  maps this to a generic workflow failure summary.

## Requirements

**Failure identity**
- R1. Preserve the actual terminal cause through strategy, service, workflow
  status, and logs. Distinguish legacy context-round exhaustion, overall
  loop-round exhaustion, output-token exhaustion, incomplete or unknown
  provider completion, empty final feedback, workspace/setup failure, provider
  request failure, and publication failure when those causes are available.
  Report an unknown cause explicitly rather than guessing.
- R2. For round-budget failures, report the limiting budget, configured limit,
  and consumed count. For output-token exhaustion, report the effective token
  limit and provider-reported output usage when available; do not substitute
  round counts for token usage. Show total turns separately from context rounds. Include
  transport mode and last normalized provider completion reason when available.
  END_TURN must not imply that the overall review completed.

**Operator diagnostics**
- R3. Existing run status and bot logs must identify the failure category and
  provide enough safe context to correlate a failed run with its PR and
  integration. Detailed logs must expose available round counts, effective
  limits, response/context sizes, and provider identity without raw request or
  response bodies. Unknown values must remain explicitly unavailable.
- R4. Detailed diagnostics must indicate when the same context request recurs
  within a run, including the number of repeated rounds. Comparison must use
  the requested tool/file operation and arguments rather than generated request
  IDs. This is an observation only: repetition must not terminate, suppress,
  deduplicate, retry, or otherwise change execution.
- R5. Added diagnostics must exclude credentials, source/file contents, prompts,
  raw model output, and raw tool arguments. PR notices must be less detailed
  than operator logs and must not expose internal endpoints or configuration
  values unrelated to the failure.

**PR feedback and compatibility**
- R6. A failed run with an identified PR and available publication client must
  attempt one short, clearly labeled failure notice on the PR, stating that
  no completed review was produced (or could be published when publication
  itself failed), the safe reason
  category, and cause-specific guidance. Round-budget notices must name the
  limiting budget and consumed/configured rounds; output-token notices must
  identify output truncation without claiming unavailable usage. They must not suggest that increasing
  a budget is guaranteed to fix a stalled run.
- R7. Expected loop failures and exceptions must share the notification policy
  so a single failed run does not produce duplicate failure notices. If
  publication fails, preserve the original review failure and report the
  notification failure separately in logs/run diagnostics. A notice must never
  change FAILED into success or submit a formal approval/request-changes.
- R8. Preserve existing completion checks, exploration budgets, tools,
  retries, context handling, review content, and formal-decision behavior.
  Never publish partial output as a completed review. A successful run produces
  its existing review without a failure notice; a no-diff skip remains a skip.
  Apply the same diagnostics policy to agentic-review clarification failures.

## Success Criteria

- A fixture with a 10-context-round limit and 20-turn overall limit requesting
  context again on turn 11 reports context exhaustion, 10 consumed context
  rounds, and 11 total turns, not overall loop exhaustion.
- Each available failure category in R1 survives into run diagnostics and a
  corresponding safe PR notice, without changing the underlying outcome.
  When the PR cannot be identified or publication is unavailable, diagnostics
  explicitly record why the notice could not be attempted.
- Repeated semantically identical requests with different generated IDs are
  identified; genuinely different requests are not reported as identical.
- A failed run makes at most one failure-notice attempt through the normal
  notification path. Notification errors remain visible without masking the
  original cause.
- Privacy checks confirm that notices and added diagnostics do not contain
  raw prompts, outputs, file contents, credentials, or raw tool arguments.
- Successful reviews, formal decisions, skipped no-diff runs, and tool execution
  remain unchanged.

## Scope Boundaries

- No server/container changes, deployment, or live PR writes during this
  brainstorm. Only read-only Compose inspection was authorized.
- No completion recovery, final-answer phase, budget increases, request
  deduplication, or automatic fallback to a different model/provider.
- No llama.cpp endpoint/chat-template/native-tool modernization in this slice.
- No new diagnostics dashboard, raw-transcript capture, or automatic replay.
- Existing raw audit/log capture policies are not expanded or redesigned;
  privacy requirements apply to the added diagnostic fields and notices.
- Other workflows' failure policies are outside scope unless a shared mechanism
  must be adapted without changing their behavior.

## Key Decisions

- Diagnostics first: confirmed budget exhaustion explains the immediate FAILED
  outcome, but does not establish why the model kept requesting context.
- A short PR failure notice is required: silence makes an explicit failure
  indistinguishable from a bot that never ran.
- Keep completion strict: better visibility must not make incomplete review
  text appear authoritative or affect formal review decisions.
- Observe repeated requests without intervening: the next fix should be based
  on evidence rather than speculative larger budgets or model-size advice.

## Dependencies / Assumptions

- The deployed image differs from this checkout in at least its logged
  llama.cpp endpoint. Planning must identify the intended target revision.
- Exact raw provider finish metadata may not be available at the strategy
  boundary; preserve the normalized reason and mark unavailable detail.
- Existing run/audit infrastructure is the intended operator surface. Its
  supported fields and failure-publication behavior require planning research.

## Outstanding Questions

### Deferred to Planning
- [Affects R1, R3][Technical] Which existing outcome/run/audit mechanisms can
  carry typed failure diagnostics without changing other workflows?
- [Affects R2][Technical] How should counts be tracked so executed context
  rounds differ from total turns and an unexecuted request at the cap?
- [Affects R4, R5][Technical] How can request equality be measured in memory
  without retaining or logging raw arguments and sensitive repository content?
- [Affects R6, R7][Technical] How can review and clarification error paths
  guarantee one notice attempt and expose posting failures?
- [Affects R1, R8][Needs research] Which revision corresponds to the deployed
  image, and which checkout differences affect the implementation target?

## Next Steps

Proceed to `/ce-plan` for structured implementation planning.
