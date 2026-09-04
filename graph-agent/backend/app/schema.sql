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

-- Added after the first release, so ALTER rather than a new column in the CREATE above:
-- an existing database has already run CREATE TABLE IF NOT EXISTS and would skip it.
ALTER TABLE agent_spec ADD COLUMN IF NOT EXISTS code           TEXT;
ALTER TABLE agent_spec ADD COLUMN IF NOT EXISTS code_edited_at TIMESTAMPTZ;

-- Uploaded reference material. One row per file, one row per page: search returns a
-- page number, so the page is the unit worth storing.
CREATE TABLE IF NOT EXISTS agent_document (
    id           UUID PRIMARY KEY,
    agent_id     UUID        NOT NULL REFERENCES agent_spec (id) ON DELETE CASCADE,
    filename     TEXT        NOT NULL,
    content_type TEXT        NOT NULL,
    byte_size    BIGINT      NOT NULL,
    page_count   INTEGER     NOT NULL,
    uploaded_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_agent_document_agent ON agent_document (agent_id);

CREATE TABLE IF NOT EXISTS agent_document_page (
    id          BIGSERIAL PRIMARY KEY,
    document_id UUID    NOT NULL REFERENCES agent_document (id) ON DELETE CASCADE,
    page_number INTEGER NOT NULL,
    content     TEXT    NOT NULL,
    -- 'simple' rather than 'english': the corpus is mixed Korean/English and English
    -- stemming would mangle the Korean. Generated, so no insert can forget to fill it.
    search_vector tsvector GENERATED ALWAYS AS (to_tsvector('simple', content)) STORED,
    UNIQUE (document_id, page_number)
);

CREATE INDEX IF NOT EXISTS idx_agent_document_page_search
    ON agent_document_page USING GIN (search_vector);
