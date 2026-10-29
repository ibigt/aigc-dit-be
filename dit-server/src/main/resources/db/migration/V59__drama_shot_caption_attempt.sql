-- Soft retry counter for caption worker exponential backoff.
ALTER TABLE drama_shot_caption
    ADD COLUMN IF NOT EXISTS attempt_count INT NOT NULL DEFAULT 0;
