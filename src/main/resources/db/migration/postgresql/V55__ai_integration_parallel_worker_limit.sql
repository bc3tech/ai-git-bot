ALTER TABLE ai_integrations ADD COLUMN IF NOT EXISTS parallel_worker_limit INT NOT NULL DEFAULT 0;
