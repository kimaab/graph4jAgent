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

-- ── 등록된 데이터베이스 ─────────────────────────────────────────────────────
-- nl2sql 도구가 질의할 수 있는 DB의 목록과, 각 DB에서 읽어온 스키마입니다.
--
-- 스키마가 에이전트 소스가 아니라 여기 있는 이유는 두 가지입니다. 스키마는 소스보다
-- 자주 바뀌고 — 컬럼 하나 추가에 에이전트를 다시 생성해야 한다면 아무도 하지
-- 않습니다 — 대상 DB가 에이전트가 도는 곳에서 닿는다는 보장이 없습니다. 등록
-- 시점에 한 번 읽어 여기 적어두면, 도구 호출은 로컬 조회 한 번으로 끝납니다.
CREATE TABLE IF NOT EXISTS datasource (
    id          UUID PRIMARY KEY,
    -- 모델이 `database` 인자로 넘기는 이름. 유일해야 하는 것은 이것이 주소
    -- 체계의 전부이기 때문입니다 — 같은 이름이 둘이면 구분할 방법이 없습니다.
    name        TEXT        NOT NULL UNIQUE,
    description TEXT        NOT NULL DEFAULT '',
    -- 'mysql' 또는 'postgresql'. 식별자 인용 문자가 갈리므로(백틱 대 쌍따옴표)
    -- SQL을 조립할 때까지 따라다닙니다.
    driver      TEXT        NOT NULL,
    host        TEXT        NOT NULL,
    port        INTEGER     NOT NULL,
    db_name     TEXT        NOT NULL,
    -- MySQL은 스키마와 데이터베이스가 같은 것이라 db_name을 그대로 씁니다.
    -- PostgreSQL은 다르므로 어느 스키마를 읽을지 따로 받습니다.
    db_schema   TEXT        NOT NULL DEFAULT '',
    username    TEXT        NOT NULL DEFAULT '',
    -- 평문입니다. 동기화 시점에 서버가 대상 DB에 접속해야 하므로 복원 가능해야
    -- 하고, 이 앱에는 키 저장소가 없습니다. API로는 절대 나가지 않습니다.
    password    TEXT        NOT NULL DEFAULT '',
    synced_at   TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS datasource_table (
    id            UUID PRIMARY KEY,
    datasource_id UUID NOT NULL REFERENCES datasource (id) ON DELETE CASCADE,
    name          TEXT NOT NULL,
    -- 대상 DB의 테이블 코멘트. 프루너가 검색하는 본문이라, 비어 있으면 그
    -- 테이블은 이름으로만 걸립니다.
    description   TEXT NOT NULL DEFAULT '',
    UNIQUE (datasource_id, name)
);

CREATE TABLE IF NOT EXISTS datasource_column (
    id          BIGSERIAL PRIMARY KEY,
    table_id    UUID    NOT NULL REFERENCES datasource_table (id) ON DELETE CASCADE,
    name        TEXT    NOT NULL,
    data_type   TEXT    NOT NULL DEFAULT '',
    description TEXT    NOT NULL DEFAULT '',
    -- 대상 DB에서의 컬럼 순서. 키가 앞에 온다는 정보가 여기 들어 있어서,
    -- 사람이 읽을 때도 모델이 읽을 때도 원래 순서가 낫습니다.
    ordinal     INTEGER NOT NULL,
    UNIQUE (table_id, name)
);

CREATE INDEX IF NOT EXISTS idx_datasource_table_ds
    ON datasource_table (datasource_id);
CREATE INDEX IF NOT EXISTS idx_datasource_column_table
    ON datasource_column (table_id);
