---
status: ready
priority: p1
source: ce-review 2026-10-07-inline-review
---

# Verify that the position returned by GitLab matches the requested anchor

hasFirstNotePosition accepts any Map. Compare new_path/old_path and new_line/old_line and treat a mismatch as MISPLACED. Update the test that uses an empty position.

