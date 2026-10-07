---
status: ready
priority: p1
source: ce-review 2026-10-07-inline-review
---

# Re-check the review fence before each GitLab discussion, summary, and action write

GitLabApiClient.publishInlineReview loops POSTs without a fence; pass a requireActive callback and stop with an accurate partial outcome.

