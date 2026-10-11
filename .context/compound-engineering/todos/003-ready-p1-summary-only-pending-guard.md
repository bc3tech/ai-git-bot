---
status: ready
priority: p1
source: ce-review 2026-10-07-inline-review
---

# Apply the Gitea pending-review guard to summary-only publication

ReviewPublicationService takes the postReview path when anchors are empty and skips the PENDING check; the formal action can submit an existing pending review.

