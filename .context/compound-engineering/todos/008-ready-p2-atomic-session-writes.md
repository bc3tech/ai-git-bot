---
status: ready
priority: p2
source: ce-review 2026-10-07-inline-review
---

# Make the session writes for a review exchange atomic and fenced

CodeReviewService writes the user and assistant messages separately after one fence check.

