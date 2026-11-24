-- One shared list: a post is one row whatever computer holds it. machine_id is now the computer that makes and
-- posts it; any other computer can take the post over (it is made again from scratch there) or delete it.

-- Project ids are random, so the same id on two computers should not exist; keep the newest if it does.
DELETE FROM posts a USING posts b
WHERE a.project_id = b.project_id AND a.machine_id <> b.machine_id
  AND (COALESCE(a.updated_at, 'epoch') < COALESCE(b.updated_at, 'epoch')
       OR (COALESCE(a.updated_at, 'epoch') = COALESCE(b.updated_at, 'epoch') AND a.machine_id < b.machine_id));

ALTER TABLE posts DROP CONSTRAINT posts_pkey;
ALTER TABLE posts ADD PRIMARY KEY (project_id);
-- what older app versions upsert on (ON CONFLICT (machine_id, project_id))
ALTER TABLE posts ADD CONSTRAINT posts_machine_project UNIQUE (machine_id, project_id);

ALTER TABLE posts
    ADD COLUMN removed_by     VARCHAR(32),                   -- computer that deleted the post when not its holder
    ADD COLUMN slide_ratio    VARCHAR(8),
    ADD COLUMN queue_scenes   INTEGER,
    ADD COLUMN queue_chibi    BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN queue_privacy  TEXT,
    ADD COLUMN queue_ai_label BOOLEAN NOT NULL DEFAULT TRUE;
