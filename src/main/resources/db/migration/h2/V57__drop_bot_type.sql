-- Remove the legacy bot type; issue behaviour is resolved from the issue workflow configuration.
ALTER TABLE bots DROP COLUMN IF EXISTS bot_type;
