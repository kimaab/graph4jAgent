CREATE TABLE IF NOT EXISTS agent_spec (
    id             UUID PRIMARY KEY,
    name           TEXT        NOT NULL,
    description    TEXT        NOT NULL DEFAULT '',
    model          TEXT        NOT NULL,
    system_prompt  TEXT        NOT NULL DEFAULT '',
    tools          TEXT        NOT NULL DEFAULT '[]',
    graph_type     TEXT        NOT NULL,
    steps          TEXT        NOT NULL DEFAULT '[]',
    max_iterations INTEGER     NOT NULL DEFAULT 10,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_agent_spec_created_at ON agent_spec (created_at);
