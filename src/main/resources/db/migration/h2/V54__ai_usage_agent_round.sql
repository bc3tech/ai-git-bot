-- V54: Record which agent-loop round produced an AI call so the Usage page
-- can show the loop position of each interaction. NULL for AI calls made
-- outside an agent loop (single-shot reviews, triage classification, ...).
-- Idempotent so the migration can be re-applied safely.

ALTER TABLE ai_usage_log ADD COLUMN IF NOT EXISTS agent_round INTEGER;
