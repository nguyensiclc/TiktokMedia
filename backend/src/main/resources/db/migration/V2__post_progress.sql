-- The posts list is the same on every computer: keep each post's progress too, so another computer can show
-- the same steps (script, pictures, swipe photos, caption) without having the project's files.

ALTER TABLE posts
    ADD COLUMN aspect            VARCHAR(8),
    ADD COLUMN scenes            INTEGER NOT NULL DEFAULT 0,  -- number of scenes
    ADD COLUMN has_script        BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN scenes_prompted   INTEGER NOT NULL DEFAULT 0,  -- scenes with an image prompt
    ADD COLUMN scenes_with_media INTEGER NOT NULL DEFAULT 0,  -- scenes that already have their picture / clip
    ADD COLUMN slides            INTEGER NOT NULL DEFAULT 0,  -- swipe photos exported
    ADD COLUMN has_caption       BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN queue_draft       BOOLEAN NOT NULL DEFAULT FALSE, -- saved as a TikTok draft instead of posted
    ADD COLUMN queue_step        TEXT,                         -- step running now (PREPARING / POSTING)
    ADD COLUMN queue_error       TEXT;                         -- why it FAILED
