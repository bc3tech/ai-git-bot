# Plan — Writer-Agent Completion and Context Continuity

**Status:** Implemented (2026-09-27) — wrap-up round, persisted native tool payload (
`conversation_messages`, Flyway `V53`) and `agent.writer.max-tool-rounds`.
**Scope:** Technical-writer agent (`WriterAgentService`, `WriterAgentStrategy`, `AgentSessionService`,
`AgentLoop`) in **both** tool modes (NATIVE and LEGACY). One migration (`conversation_messages`,
`h2` + `postgresql`). One new environment variable. **No** new session status, so no
`agent_sessions` CHECK-constraint work.
**Settled decisions:** a dedicated wrap-up round (loop hard cap `maxToolRounds + 2`) instead of
reusing the discarded sixth round; full native history replay (persist `tool_calls` /
`tool_call_id`) instead of a lossy context digest; `agent.writer.max-tool-rounds` added with the
default left at 5; the session-level 80,000-character compaction threshold stays unchanged and is
recorded as a follow-up.
**Origin:** issue thread [#417](https://github.com/tmseidel/ai-git-bot/issues/417) (reporter
@bjuraga, self-hosted Ollama `qwen3.6:35b-a3b`, technical-writer bot on issue #28 of a monorepo).
Evidence: the raw log attached to that thread — agent session 17, six writer runs, 17:27–18:26 UTC.
The sibling fix for the coding agent is [#418](https://github.com/tmseidel/ai-git-bot/issues/418)
(`doc/development-archive/answer-only-completion-architecture.md`); this plan covers the
**writer**, which that change deliberately did not touch.

---

## 1. Problem

### 1.1 What the reporter sees

The writer never produces an improved issue. It answers five times in a row with

> ⚠️ **AI Technical Writer**: I need more context before I can continue. Please add more details and
> mention me again.

and, after the session has been compacted, once with

> 🤖 **AI Technical Writer** — Quality assessment: Cannot assess — all context from the previous
> session was compacted and lost.

Replying `continue @bot` does not help: every run restarts the same investigation, burns the same
budget and returns the same message.

### 1.2 Those are two different branches, not one bug

| message | branch | condition |
|---------|--------|-----------|
| "I need more context before I can continue…" | `WriterAgentStrategy:107-114` (NATIVE, tool-call turn) / `:202-208` (LEGACY, JSON envelope) | `writerRound = round - 1 >= maxToolRounds` (5), evaluated **before** the round's tools are executed |
| "Quality assessment: Cannot assess — …" | `WriterAgentStrategy:224-229` | the model returned a plan with `readyToCreate=false` after compaction had replaced its history with a placeholder |

### 1.3 The log, run by run (agent session 17, issue #28)

| run | window (UTC) | persisted session at start | replayed history, round 1 | loop rounds | tool calls requested | ending |
|-----|--------------|-----------------------------|---------------------------|-------------|----------------------|--------|
| task-4 | 17:27 → 17:42 | not in the pasted log (the paste starts mid-run) | not logged | 1-6 | 14 (3 discarded) | "need more context" |
| task-5 | 17:42 → 17:46 | 34,296 chars | 1 msg | 1-6 | 14 (3 discarded) | "need more context" |
| task-6 | 17:47 → 17:52 | 38,679 chars | 3 msgs | 1-6 | 10 (2 discarded) | "need more context" |
| task-7 | 17:53 → 18:01 | 46,072 chars | 4 msgs | 1-6 | 15 (3 discarded) | "need more context" |
| task-8 | 18:04 → 18:09 | 70,310 chars | 5 msgs | 1-6 | 10 (3 discarded) | "need more context" |
| task-9 | 18:20 → 18:26 | 84 msgs → compacted to 9 | 1 msg | 1-2 | 2 | "Cannot assess — context lost" |

Every cap-out run has the same shape: rounds 1-5 come back `reason=TOOL_USE` with 1-5 tool calls,
the model never emits a terminal turn, and round 6 — which again carries 2-3 tool calls — is cut
off before execution (the comment is posted 9 ms after that AI reply, with no `Executing tool:`
line in between). Session prompt tokens grew 46,525 → 229,408, wall time ≈ 44 minutes, six
comments, zero progress.

### 1.4 Root causes, by severity

1. **The writer has no wrap-up round.** `WriterAgentStrategy:107-114` ends the run the moment the
   round budget is spent, instead of giving the model one round to answer with what it has. The
   model is never told the budget exists: neither the DB system prompt nor the output contract
   (`WriterAgentService:353-370`, "You may use requestFiles or read-only repository requestTools
   when existing issue or repository context is needed") mentions a limit, so a tool-calling model
   keeps exploring until the strategy consents to it — which it never does. The one-shot nudge at
   `:96-104` only fires on a **text-only** turn, and Ollama never produces one.
   Contrast the coding agent: 10 context rounds + 10 tool rounds (`application.properties:35-36`)
   and, since #418, an explicit nudge that names the exit. Nothing pins the writer's cap branch
   either — `src/test/java/.../agent/writerimpl/` contains only `WriterResponseParserTest`.
2. **Everything the model read is discarded between runs.** `AgentLoop.run` seeds the working
   history from `AgentSessionService#toAiMessages` (`AgentSessionService:350-362`), which drops
   every `role:"tool"` message and every blank-content assistant turn, because
   `org.remus.giteabot.session.ConversationMessage` persists only `role` + `content` (`:13-38`) —
   no `tool_calls`, no `tool_call_id`. Replaying the stored pairs raw would be rejected by the
   provider, so they are dropped. Result in the log: a session holding 34K-70K characters starts
   each new run with 1-5 replayed messages — only the previous runs' *user prompts* survive. Every
   `continue` therefore re-runs the same investigation from the same starting point with the same
   5-round budget, which is why the message repeats verbatim. The thread's advice ("the agent
   keeps its knowledge about the past 5 tool-calls and gets new 5") does not hold in NATIVE mode:
   it keeps none of them.
3. **Session compaction then erases even the prompt trail.** `compactContextWindow`
   (`AgentSessionService:251`, threshold `COMPACT_THRESHOLD_CHARS = 80_000`, keep
   `MAX_MESSAGES_AFTER_COMPACT = 8`) fired on run 6: 84 messages / 83,098 chars → 9 messages /
   9,251 chars. The survivors are one generic placeholder plus recent tool/blank turns, and the
   replay filter reduces those to a single message — the `history=1 msgs` in the log. The model's
   "Cannot assess — context lost" reply is that placeholder doing its job. The threshold is a
   fixed character count, so raising the model's context window (the reporter went to 130K) cannot
   prevent it.
4. **The cap is fixed and not operator-settable.** `WriterConfig.maxToolRounds = 5`
   (`AgentConfigProperties:121-133`) has no `agent.writer.*` mapping anywhere in
   `src/main/resources` (`grep -rn "agent.writer"` → nothing), unlike its triage counterpart
   `AGENT_TRIAGE_MAX_TOOL_ROUNDS` (`application.properties:23`). The maintainer announced this
   issue in the thread; it was never filed.
5. **The failure is shaped like a success.** The cap branch sets status `IN_PROGRESS` and returns
   `LoopOutcome.success(..., null)` (`:109-113`, `:203-207`), so nothing signals a problem, the
   session stays claimable and the next `@mention` repeats the cycle. `onBudgetExhausted`
   (`:249-253`) is a silent no-op success — if the loop cap were ever reached instead, the user
   would get no comment at all.
6. **In-loop compaction destroys gathered context mid-run, too.** `AgentLoop:126-163` +
   `HistoryCompactor:32-35` replace older tool results with *"Previous tool results are no longer
   available in history but their effects persist. You can re-read files or re-run tools if
   needed."* The maintainer's own reproduction shows it firing at round 4 of 6 (105.4 % usage). The
   advice costs exactly the rounds the run no longer has.

## 2. Goal / invariants

1. A writer run **always ends with a usable outcome**: an improved issue is created, a
   clarifying-questions comment is posted, or the author is asked for details — never a silent
   end and never a repeated "I need more context" while the model was still working.
2. The model gets exactly one explicit chance to conclude before the run ends, and it is told the
   context budget is gone when that chance is given.
3. A follow-up run **resumes** the gathered context instead of restarting: whatever the previous
   runs read is available to the model again, provider-valid, without re-running the tools.
4. Existing behaviour for `.writer` deployments stays intact unless an operator opts in: the
   default round budget is unchanged at 5, and the LEGACY JSON protocol keeps working.
5. No new session status, no change to the `agent_sessions` CHECK constraint, no UI/i18n surface.

## 3. Trigger

`WriterAgentStrategy#step(...)` when a round arrives while the repository-context budget is spent:

* NATIVE: `step(AgentRunContext, ChatTurn, int)` with a turn that **has** tool calls and
  `round - 1 >= maxToolRounds` (`:107-114`) — today this discards the calls and ends the run.
* LEGACY: `step(AgentRunContext, String, int)` with a parsed plan that **has** context requests
  and `round - 1 >= maxToolRounds` (`:202-208`) — same behaviour today.

Both branches are replaced; nothing else in the strategy's decision tree changes.

## 4. Design

### 4.1 Policy — one wrap-up round, then a real outcome

`n = agent.writer.max-tool-rounds` (default 5), `writerRound = round - 1` (unchanged 0-based
arithmetic).

| # | condition | decision |
|---|-----------|----------|
| R1 | tool calls, `writerRound < n` | unchanged: execute them, `ContinueWithToolResults` |
| R2 | tool calls, `writerRound == n` | **wrap-up**: execute nothing; return `ContinueWithToolResults(skipped, WRAP_UP)` — one synthetic result per call id plus the wrap-up instruction in the `follow` slot |
| R3 | tool calls, `writerRound > n` | post the existing "I need more context…" comment, `Finish(success)`, status `IN_PROGRESS` (unchanged wording, now reachable only after the wrap-up was offered) |
| R4 | no tool calls | unchanged paths: one-shot JSON nudge (`:96-104`), then context requests settle into R2/R3 by the same `writerRound` comparison, otherwise the clarifying-questions branch (`:224-229`) or issue creation (`:231-245`) |

**Update (2026-09-28) — the answer round keeps its tools.** An earlier revision sent that round
without tool descriptors. That only looked safe: an empty tool list makes the clients fall back to
their plain-text message shape, which cannot represent the replayed tool exchanges — a turn whose only
content was its calls rendered as an empty message, which Anthropic and Gemini reject — so the round
failed on the runs it exists to save. The descriptors therefore stay, the wrap-up instruction is what
asks for the answer, a model that still calls tools lands on R3's comment, and the operator lever is
`agent.writer.max-tool-rounds` (the cap moves the wrap-up with it). For the other direction — a native
session replayed in legacy mode — the plain-text converters now name such a turn's calls
(`[called cat]`, `AiMessage#toolCallSummary`).

R2 and R3 are bounded by construction — R2 can fire only in round `n + 1`, R3 only after it — so no
new strategy state is required (unlike the coding agent's `answerNudges`, which needed a counter
because its trigger was a turn *shape*, not a round number).

**Why R2 returns tool results instead of a bare `Continue`.** `AgentLoop:205-218` records the
assistant turn *with* its `tool_calls` payload, because OpenAI/Anthropic/Ollama reject a request
whose call ids are never answered. A bare `Continue` would therefore leave an unanswered
`tool_calls` turn in the request; a synthetic result per call id (text: *"not executed — the
writer's repository-context budget is exhausted for this run"*) keeps the provider contract valid
and carries the instruction in the existing `follow` slot (`StepDecision.ContinueWithToolResults`,
already used by this strategy at `:142`).

**LEGACY path** (`:202-208`): the JSON envelope carries no call ids, so R2 there is a plain
`Continue(WRAP_UP)`; R3 keeps today's comment-and-finish.

### 4.2 Budget arithmetic

`WriterAgentService:256-259` currently sets the loop hard cap to `maxToolRounds() + 1`, which makes
round `n + 1` the give-up round. It becomes `maxToolRounds() + 2`:

| round | role with n = 5 |
|-------|-----------------|
| 1-5 | context rounds (R1) |
| 6 | wrap-up instruction delivered (R2) |
| 7 | the model's answer — plan with `readyToCreate=true`, clarifying questions, or R3 |

The hard cap stays derived, so a raised `agent.writer.max-tool-rounds` moves the wrap-up with it.

### 4.3 The wrap-up instruction

New `WriterPromptBuilder#buildWrapUpInstruction()`, delivered as the follow-up message of R2's
tool-result round. It must name all three exits and state that the tools are gone:

```
## Context rounds exhausted

You have used all repository-context rounds for this run and cannot call tools any more.

Return your final JSON answer now, from what you have already read:
- If the issue can be improved with that information, set "readyToCreate": true and fill
  "revisedIssueDraft" (plus "assumptions" for anything you inferred).
- If a fact is still missing, put the specific question in "clarifyingQuestions" and name the file
  or behaviour you could not verify.

A further tool request ends this run and the author is asked for details instead.
```

It is generated at runtime (like `buildToolFeedback`), so it needs no Flyway migration and cannot
clobber an operator's edited `system_prompts` row. Being LLM-facing, the text stays English.

**Prerequisite:** the model must know the budget exists before it is spent. `outputContract()`
(`WriterAgentService:353-370`) gains one line naming the limit and the ordering advice — *"You have
N repository-context rounds in total (`agent.writer.max-tool-rounds`). Prefer the few files that
matter most and keep your final answer for the last round."* Also runtime text, also no migration.

### 4.4 Configurable writer round budget

* `application.properties`, next to the triage pair (`:23-24`):
  `agent.writer.max-tool-rounds=${AGENT_WRITER_MAX_TOOL_ROUNDS:5}`
* `docker-compose.yml` `environment:` block: `AGENT_WRITER_MAX_TOOL_ROUNDS:-5`
* `doc/USER_GUIDE.md`: one row in the `## Configuration Reference → ### Environment Variables`
  table plus a sentence in the neighbouring prose subsection (runtime/behaviour setting — **not**
  `doc/DEPLOYMENT.md`, and not the four-language READMEs).
* No profile file: it would layer on top of the base instead of replacing it.
* Default stays 5 — operators affected by this report can raise it once the wrap-up exists; the
  plan deliberately does not change behaviour for everyone.
* **Interim shape:** this is one more per-agent budget property, exactly the kind §10 item 5
  removes. It lands now because the writer's cap is unreachable in any form (§1.4 item 4); when
  the caps move to a single home, this key becomes an alias of the shared
  `agent.budget.max-tool-rounds` rather than a writer-only preference.

### 4.5 Rejected: reuse today's discarded sixth round

Keeping the hard cap at `maxToolRounds + 1` and turning round 6 into the instruction would save one
AI call per run, but leaves only 4 executed context rounds — i.e. it fixes "no chance to answer" at
the cost of the gathering capacity that this report says is insufficient. With `n` configurable,
operators who care about cost can lower `n` instead, which buys the same saving explicitly.

### 4.6 Cross-run continuity — persist and replay the native tool payload

The writer's `continue` loop is only usable if the second run sees what the first read. Minimum
provider-valid change:

**Schema** (one migration, `h2` + `postgresql`, CRLF):

```sql
ALTER TABLE conversation_messages ADD COLUMN IF NOT EXISTS tool_call_id VARCHAR(255);
ALTER TABLE conversation_messages ADD COLUMN IF NOT EXISTS tool_calls   TEXT;
```

`ADD COLUMN IF NOT EXISTS` is the form already used in `V5__technical_writer_agent.sql` in both
dialects. `conversation_messages` is declared in `V1__init_schema.sql:87-95` (h2) and
`:107+` (postgresql) with the `agent_session_id` FK.

**Entities and plumbing**

* `ConversationMessage` (`:13-38`) gains `toolCallId` + `toolCalls` (JSON string); `AgentSession`
  gets an `addMessage(role, content, toolCallId, toolCalls, createdAt)` overload next to the
  existing `:167`/`:181` helpers.
* `PendingMessage` (2-component record, **21 construction sites: 5 main + 16 test**) gains a nested
  payload record (`record ToolPayload(List<ToolCall> toolCalls, String toolCallId)`) plus a 2-arg
  convenience constructor, so only the three `AgentLoop` sites that actually carry tool data
  change. Adding record components instead would break all 21 sites for no benefit.
* `AgentLoop`: the assistant pending message (`:106`) carries `turn.toolCalls()` serialized with
  `AgentJackson.mapper()` (skip empty), and the tool pending message (`:232-233`) carries
  `r.toolCallId()` with the **raw** result text as `content` (today the id is prefixed into the
  content: `"[" + id + "] " + text`). Keeping the prefix in new rows would push transport
  bookkeeping into the replayed prompt; old rows keep it and are dropped on replay anyway.
* `AgentSessionService#flushMessages` (`:109-125`) passes both fields through
  `managed.addMessage(...)`.

**Replay rules in `toAiMessages`** (`:350-362`) — replace "drop all tool/blank turns" with:

| stored row | replayed as |
|------------|-------------|
| assistant with `tool_calls` | assistant message **with** its `toolCalls` payload (blank content is the normal native shape and is now kept) |
| assistant without `tool_calls`, blank content | dropped (unchanged: an orphan tool-call-only turn from before this migration) |
| tool whose `tool_call_id` matches an id declared by the immediately preceding assistant turn | tool message with `toolCallId` + `toolResult` |
| tool with a `NULL` `tool_call_id` (pre-migration rows) or an id no surviving assistant turn declares | dropped (unchanged behaviour for old sessions and for pairs broken by truncation) |
| user | unchanged |

Ordering stays `createdAt` ascending — `flushMessages` already stamps a batch 1 µs apart
(`:112-117`), which is what makes pairing deterministic.

**Compaction must not split a pair.** `AgentSessionService#compactContextWindow` removes "all but
the most recent 8" rows and can cut between an assistant `tool_calls` row and its tool rows,
leaving a kept tool row without its declaration (the replay table above would then drop the
results — the very loss this section removes). Extend the kept-window boundary so a pair is kept or
removed as a unit. `HistoryCompactor#groupIntoUnits` (`:146-186`) already does exactly this for the
in-loop history ("An assistant message with tool_calls and all subsequent tool messages that
reference those calls form one unit") — copy that unit logic rather than inventing a second rule.

### 4.7 Migration hygiene

* Take **V53**, not V52. Version 52 is claimed by four refs
  (`bugfix/exit-branch-no-diff`, `feature/model-routing`, `feature/model-routing-deepseek`,
  `CaeruleusAqua/feat/openrouter-routing-settings`); 53 is free as a file at every ref tip.
  Re-run the check before writing the file —
  `git log --all --name-only --pretty=format: -- src/main/resources/db/migration/ | grep -E 'V5[0-9]__' | sed 's#.*/##' | sort -u`
  — because a collision does not fail loudly, it silently skips the other script.
* **The developer's local database needs two rows removed first** (ask, then run it there):

  ```sql
  DELETE FROM "flyway_schema_history" WHERE "version" IN ('52', '53');
  ```

  Measured against a copy of `data/giteabot.mv.db`: `migrate()` — which is what the app does at
  startup — currently **fails** with `Migration checksum mismatch for migration version 52`,
  because the h2 script was comment-edited after it had been applied at 19:04 that evening. The
  rename from 53 to 52 also left an orphan version-53 row. Both scripts are idempotent
  (`DROP CONSTRAINT IF EXISTS` + `ADD`), so deleting the rows lets Flyway re-apply them and record
  the correct checksums. Never `Flyway.repair()` a version *collision*, and never hand-write a
  checksum back into a row.

## 5. Behaviour by scenario

| scenario | before | after |
|----------|--------|-------|
| model keeps calling tools for the whole budget (the report) | round 6 comment, `Continue` never returned to the model, no outcome | round 6 wrap-up instruction, round 7 answer → issue draft or questions |
| model still requests tools after the wrap-up | — (never reached) | existing "I need more context" comment, unchanged wording |
| model answers before the budget is spent | clarifying-questions / issue-creation branch | unchanged (R4) |
| text-only narration in NATIVE mode | one-shot JSON nudge, then the legacy path | unchanged |
| `continue @bot` after a cap-out run | restarts from scratch, same message | replays the previous runs' tool results, fewer re-reads, wrap-up available again |
| session over 80,000 chars | compaction wipes everything but the last 8 rows, pairs split | compaction keeps pairs together; replay rebuilds valid tool exchanges |
| LEGACY (`use_legacy_tool_calling=true`) | same cap-out, no model feedback | wrap-up instruction as plain text, then R3 |
| operator raises `AGENT_WRITER_MAX_TOOL_ROUNDS` | not possible | context rounds scale, wrap-up follows automatically |

## 6. Cost and bounds

* Today: 6 AI calls per run, of which the sixth is always discarded, plus 5-15 wasted tool calls
  per thread; measured ≈44 minutes of model time across the six runs in the log, 229,408 cumulative
  prompt tokens for the session — and not one usable outcome.
* After: at most `n + 2` calls per run, and the run ends with an outcome. Cost per *successful*
  attempt rises by one call (the wrap-up) — that call replaces a guaranteed failure, not a
  successful run.
* Replay increases the first prompt of every follow-up run (up to what survived compaction). The
  in-loop truncation/compaction and the session-level threshold still apply, so the bound is the
  80,000-character session budget, not unbounded growth.
* No new AI call pattern, no change to `AgentBudget` for the coding agent, no new status value.

## 7. Tests

`WriterAgentStrategyTest` — **new class** (only `WriterResponseParserTest` exists today; mirror the
structure of `CodingAgentStrategyTest`):

* R1 unchanged: context round with `writerRound < n` executes and returns
  `ContinueWithToolResults`.
* R2: at `writerRound == n` nothing is executed, every call id gets a skipped result, and the
  `follow` text is the wrap-up instruction (assert the text names both exits and "tools").
* R3: at `writerRound > n` the "I need more context" comment is posted and the outcome is
  `success` — the branch that used to fire at `n` is now reachable only after the wrap-up.
* Wrap-up fires exactly once across a simulated run (rounds `n`, `n+1`, `n+2`).
* A plan with `requestFiles`/`requestTools` arriving at the wrap-up round takes R2 (legacy path).
* The clarifying-questions branch and issue creation still win when the model answers at or before
  the wrap-up round.

`WriterAgentServiceTest`: the loop's hard cap equals `maxToolRounds + 2`, and follows a raised
`agent.writer.max-tool-rounds` (constructor/threshold test, no Spring context).

`AgentLoopNativeToolResultTest` / `AgentSessionServiceTest`: the assistant turn is persisted with
its `tool_calls` payload and tool rows with their `tool_call_id`; `toAiMessages` rebuilds the pair;
pre-migration rows (NULL ids) and orphan tool rows are still dropped; a pair split by compaction
never yields a kept-but-unpaired tool row (`AgentSessionCompactionIntegrationTest` is the model).

**Migration gate** — version-agnostic, because the sibling probe died on a pinned
`flyway("53")`: apply migrations to latest in a standalone Flyway probe pointed at
`filesystem:src/main/resources/db/migration/h2` and assert the two columns exist via
`INFORMATION_SCHEMA.COLUMNS`; plus a `@SpringBootTest @ActiveProfiles("test")` round trip that
persists and re-reads a message with `tool_calls`/`tool_call_id`. Never assert on a hard-coded
version number.

## 8. Non-goals

* **Session-level compaction threshold** — decided to stay at 80,000 characters; see §10.
* **Raising the default writer budget.** Configurable, default unchanged.
* **Other agents.** The coding agent's answer path shipped with #418; the triage and review agents
  have their own shapes (see §10) and are not part of this change.
* **Unifying the budget properties.** §4.4 deliberately adds one more per-agent cap (the writer's
  cap is unreachable today); folding every agent's budget caps into a single home is §10 item 5.
* **UI/i18n.** No status, no enum, no template, no message bundle is touched. The new instruction
  and the contract line are LLM-facing and stay English.
* **Docker-compose-only repositories** (no validation tool) from the same report — separate issue.

## 9. Trade-offs and risks

* Every writer run that exhausts its budget pays one extra AI call. Accepted: it replaces a
  guaranteed failure, and `agent.writer.max-tool-rounds` lets operators trade context rounds back.
* A model that still insists on tools after the wrap-up gets exactly today's message — the fix is
  not a guarantee that a weak model concludes well, only that it is asked once, explicitly, with
  the tools gone.
* Replayed tool results are a *snapshot*: a file can change between runs (the author edits the
  repo, or the earlier run was days ago). The issue text stays the source of truth and the model
  can still re-read with the tools the wrap-up leaves it — stale reads are no worse than today's
  "no reads at all".
* Prompt growth from replay can push a small-window model into the in-loop compactor earlier,
  which then replaces tool results with a summary — the very mechanism this change works around.
  Mitigation is the 80,000-character session threshold plus, for affected operators, the
  configurable budget; if it proves insufficient, §10 item 2 is the next lever.
* `PendingMessage` gaining a payload must keep the 2-arg constructor: 16 test sites plus 5 main
  sites construct it, and a component change would touch all 21 for no benefit.

## 10. Follow-ups (not in this change)

1. **Triage agent budget.** `TriageAgentStrategy` counts context rounds only in the legacy path
   (`:198`); the NATIVE path executes a context turn every round (`:175`) and relies on the loop
   cap, where the model is repeatedly told to produce its routing JSON without being told the
   context budget is gone. It fails loudly (`onBudgetExhausted` returns a reason) rather than
   silently, which is why it is not folded in here — but it deserves the same wrap-up instruction.
2. **Session compaction threshold.** Currently a fixed 80,000 characters / keep last 8
   (`AgentSessionService:29,36`), independent of the model's window; raising the Ollama window, as
   the reporter did, changes nothing. Consider tokens derived from the resolved integration, or a
   configurable value.
3. **Metering the give-up path.** The R3 comment still ends as `IN_PROGRESS` + `success`, so
   repeated cap-outs are invisible in monitoring. A counter (or a distinct status, which would
   then need the CHECK-constraint migration) is the follow-up; the coding agent's
   `giteabot.agent_sessions{status}` gauge is the precedent.
4. **In-loop compaction message.** `HistoryCompactor:32-35` tells the model to "re-read files",
   which costs rounds a nearly exhausted run does not have; word it as an instruction to conclude.
5. **One home for the agent budget caps.** Every agent should read its caps from
   `org.remus.giteabot.config.AgentConfigProperties` and from nowhere else — no per-agent budget
   preferences, and no cap arithmetic inside the agent classes. Today there are four homes:
   * **The shared block that is not the whole story.** `agent.budget.*`
     (`application.properties:35-42` → `BudgetConfig`) is what all four budgeted agents build their
     `AgentBudget` from (`IssueImplementationService:327`, `WriterAgentService:256`,
     `IssueTriageService:216`, `AgentReviewService:622`) — but each of them honours a further cap
     from somewhere else.
   * **Per-agent cap fields beside it:** `TriageConfig#maxToolRounds`
     (`agent.triage.max-tool-rounds`, `:23`, duplicated into `application-docker.properties:14`),
     `WriterConfig#maxToolRounds` (§4.4, currently unmapped) and `ValidationConfig#maxToolExecutions`
     — which is the *coding* agent's tool-round cap (`CodingAgentStrategy:138`) and is **also
     unmapped**, so it is not operator-settable at all.
   * **Workflow params, per workflow configuration row and editable in the UI:**
     `AgentReviewParam.MAX_TOOL_ROUNDS`, `I18nCoverageParam.MAX_TOOL_ROUNDS`,
     `ReadmeSyncParam.MAX_TOOL_ROUNDS`, with labels in all seven `messages*.properties`.
   * **Cap arithmetic re-implemented per agent:** `Math.max(BASELINE_ROUNDS, Math.min(...))` and
     `OVERHEAD_ROUNDS`-style constants in `ReadmeSyncAgent`, `I18nCoverageAgent`,
     `UnitTestAuthorAgent`, `TestAuthorAgent`, `TestRunnerAgent`, plus
     `clamp(maxToolRounds, 1, 30)` in `AgentReviewService:620`. "Rounds" therefore means a
     different thing per agent, and no single place answers "what is the cap for agent X".
   Why it matters beyond tidiness: the defaults already contradict each other — `BudgetConfig`'s
   field defaults are 10/3/3/5/120,000 while the shipped `${ENV:default}` values are
   20/10/10/10/180,000 — and `doc/USER_GUIDE.md:743` documents an `AGENT_VALIDATION_MAX_RETRIES`
   that exists in no code path (the real key is `AGENT_BUDGET_MAX_VALIDATION_RETRIES`, documented
   again in `doc/AGENT.md:101` and `doc/CODING_AGENT.md:55`). Three doc sites describe the same caps.
   Work: move `TriageConfig#maxToolRounds` and `WriterConfig#maxToolRounds` into the shared block
   (`agent.budget.max-tool-rounds`), fold `ValidationConfig#maxToolExecutions` in with them, lift the
   per-agent clamp constants into the shared budget arithmetic so a cap means the same thing
   everywhere, and decide per workflow param whether it survives as an **override on** the shared cap
   or goes away — it is a shipped UI feature with seven message bundles, not something to delete
   silently. Then collapse the env-var documentation to one table (today: `doc/USER_GUIDE.md`
   "Agent Configuration", `doc/AGENT.md`, `doc/CODING_AGENT.md`) with a deprecation note for
   `AGENT_TRIAGE_MAX_TOOL_ROUNDS` and for the typo'd `AGENT_BUDGET_MAX_CONTENT_ROUNDS`
   (`docker-compose.yml:29`).
   The same treatment applies to the context-size limits — `agent.context.*`, `review.context.*`
   (`ReviewConfigProperties`) and `review.chunking.*` (`ReviewChunkingProperties`) are character/file
   budgets in all but name, and today they sit in three more prefixes.
   Constraint from model routing: one shared cap is only safe while the token-derived part of the
   budget still comes from the **resolved** integration, never from `bot.getAiIntegration()`
   (`references/model-routing.md`: budgets must not be computed for one integration and spent
   against another) — so the single home takes the resolved integration as an input.

## 11. Implementation order

1. Migration `V53__conversation_message_tool_payload.sql` (`h2` + `postgresql`) + dev-database row
   cleanup (§4.7) + the version-agnostic schema probe.
2. `ConversationMessage` / `AgentSession.addMessage` / `PendingMessage` payload /
   `flushMessages` — persistence only, no behaviour change yet.
3. `AgentLoop` writes the payload, `toAiMessages` rebuilds the pairs (with the drop rules for
   legacy rows), `compactContextWindow` keeps pairs together; tests for all three.
4. Wrap-up round in `WriterAgentStrategy` (NATIVE R2/R3, then the LEGACY variant) +
   `WriterPromptBuilder#buildWrapUpInstruction` + `WriterAgentStrategyTest`.
5. `WriterAgentService`: hard cap `maxToolRounds + 2`, budget line in `outputContract()`, test.
6. `agent.writer.max-tool-rounds` in `application.properties` + `docker-compose.yml` (interim cap:
   §10 item 5 folds it into the shared block).
7. Docs: `CHANGELOG.md` (Unreleased → Fixed for the behaviour, → Added for the env var),
   `doc/AGENT.md` (writer completion contract), `doc/USER_GUIDE.md` (env row + prose), status line
   of this document.
8. Full suite green, then a manual run against a local Ollama model on a read-only issue to
   confirm the wrap-up produces an issue draft or a specific question.

**Open at implementation time:** whether the four-language READMEs need a capability note.
Recommendation: **no** — this is a behaviour fix plus an environment variable, which the project's
convention routes to `CHANGELOG.md` + `doc/AGENT.md` + `doc/USER_GUIDE.md`, avoiding the
`README.ja/ko/zh` mirroring burden. Confirm before touching `README.md`.
