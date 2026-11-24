-- Shared posts list: every computer running Media Studio writes its own posts here (names and states only,
-- never pictures) and reads the others', to warn before making the same topic twice.

CREATE TABLE machines (
    id         VARCHAR(32) PRIMARY KEY,          -- random id kept in ~/.mediastudio/machine-id
    name       TEXT        NOT NULL,             -- shown to the other computers (Settings → machine name)
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE posts (
    machine_id   VARCHAR(32) NOT NULL REFERENCES machines (id) ON DELETE CASCADE,
    project_id   VARCHAR(64) NOT NULL,           -- project id on that computer
    name         TEXT        NOT NULL,
    topic        TEXT        NOT NULL DEFAULT '',
    niche        VARCHAR(16),
    -- QUEUED, PREPARING, READY, POSTING, POSTED, DRAFTED, FAILED, NONE (outside the posts list)
    state        VARCHAR(16) NOT NULL,
    scheduled_at TIMESTAMPTZ,
    posted_at    TIMESTAMPTZ,
    created_at   TIMESTAMPTZ,
    updated_at   TIMESTAMPTZ,
    deleted      BOOLEAN     NOT NULL DEFAULT FALSE, -- project deleted on its computer: topic still remembered
    PRIMARY KEY (machine_id, project_id)
);

CREATE INDEX posts_machine_updated ON posts (machine_id, updated_at DESC);
