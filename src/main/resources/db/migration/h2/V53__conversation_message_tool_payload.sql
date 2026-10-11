-- Native tool-call continuity across runs: a follow-up agent run replays the
-- persisted assistant tool_calls payload and the tool_call_id each tool row
-- answers, so a rebuilt tool exchange is a valid pair instead of an orphaned
-- tool message (which OpenAI/Anthropic reject).
ALTER TABLE conversation_messages ADD COLUMN IF NOT EXISTS tool_call_id VARCHAR(255);
ALTER TABLE conversation_messages ADD COLUMN IF NOT EXISTS tool_calls TEXT;
