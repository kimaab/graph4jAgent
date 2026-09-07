"""등록된 데이터베이스와, 각 DB에서 읽어온 스키마.

두 가지 일을 합니다. 데이터소스 CRUD와, 대상 DB에 붙어 카탈로그를 읽어
`datasource_table`/`datasource_column`에 적는 동기화입니다.

동기화를 등록과 분리한 이유는 실패의 성격이 다르기 때문입니다. 등록은 우리 DB에
쓰는 일이라 거의 실패하지 않고, 동기화는 남의 DB에 붙는 일이라 방화벽·권한·오타로
늘 실패합니다. 하나로 묶으면 접속이 안 될 때 등록 자체가 안 되고, 고쳐서 다시
시도할 대상조차 남지 않습니다.
"""

import logging
from typing import Any
from uuid import UUID, uuid4

from . import db
from .errors import ApiException
from .models import (
    Datasource,
    DatasourceColumn,
    DatasourceInput,
    DatasourceTable,
    Driver,
    SyncResult,
)

log = logging.getLogger(__name__)

# 남의 DB에 붙는 데 허용하는 시간. 방화벽에 막히면 기본값은 분 단위로 매달리고,
# 그동안 요청 하나가 워커를 붙들고 있습니다.
CONNECT_TIMEOUT = 10


# ── 저장 ───────────────────────────────────────────────────────────────────

def find_all() -> list[Datasource]:
    rows = db.query(
        """
        SELECT d.*, (SELECT count(*) FROM datasource_table t
                      WHERE t.datasource_id = d.id) AS table_count
          FROM datasource d
         ORDER BY d.name
        """
    )
    return [_row(r) for r in rows]


def find_by_id(datasource_id: UUID) -> Datasource | None:
    row = db.one(
        """
        SELECT d.*, (SELECT count(*) FROM datasource_table t
                      WHERE t.datasource_id = d.id) AS table_count
          FROM datasource d WHERE d.id = %s
        """,
        datasource_id,
    )
    return _row(row) if row else None


def insert(spec: DatasourceInput) -> Datasource:
    _guard_duplicate_name(spec.name)
    new_id = uuid4()
    db.execute(
        """
        INSERT INTO datasource
            (id, name, description, driver, host, port, db_name, db_schema,
             username, password)
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
        """,
        new_id,
        spec.name,
        spec.description,
        spec.driver.value,
        spec.host,
        spec.port,
        spec.db_name,
        spec.db_schema,
        spec.username,
        spec.password,
    )
    saved = find_by_id(new_id)
    assert saved is not None
    return saved


def update(datasource_id: UUID, spec: DatasourceInput) -> Datasource | None:
    """@return 저장된 데이터소스. 그런 id가 없으면 None.

    비밀번호를 비워 보내면 기존 값을 유지합니다. 조회 응답에 비밀번호가 없으니
    편집 폼은 그것을 되돌려 보낼 수 없고, 빈 값을 그대로 쓰면 이름 하나 고칠
    때마다 접속이 끊깁니다.
    """
    _guard_duplicate_name(spec.name, except_id=datasource_id)
    rows = db.execute(
        """
        UPDATE datasource SET
            name = %s, description = %s, driver = %s, host = %s, port = %s,
            db_name = %s, db_schema = %s, username = %s,
            password = CASE WHEN %s = '' THEN password ELSE %s END,
            updated_at = now()
        WHERE id = %s
        """,
        spec.name,
        spec.description,
        spec.driver.value,
        spec.host,
        spec.port,
        spec.db_name,
        spec.db_schema,
        spec.username,
        spec.password,
        spec.password,
        datasource_id,
    )
    return find_by_id(datasource_id) if rows > 0 else None


def delete_by_id(datasource_id: UUID) -> bool:
    return db.execute("DELETE FROM datasource WHERE id = %s", datasource_id) > 0


def find_schema(datasource_id: UUID) -> list[DatasourceTable]:
    """저장된 스키마 전체. 편집기에서 "무엇이 읽혔는지" 보여주기 위한 것입니다."""
    rows = db.query(
        """
        SELECT t.name AS table_name, t.description AS table_description,
               c.name AS column_name, c.data_type, c.description AS column_description
          FROM datasource_table t
          LEFT JOIN datasource_column c ON c.table_id = t.id
         WHERE t.datasource_id = %s
         ORDER BY t.name, c.ordinal
        """,
        datasource_id,
    )

    tables: dict[str, DatasourceTable] = {}
    for row in rows:
        table = tables.get(row["table_name"])
        if table is None:
            table = DatasourceTable(
                name=row["table_name"],
                description=row["table_description"] or "",
                columns=[],
            )
            tables[row["table_name"]] = table
        # LEFT JOIN이라 컬럼이 하나도 없는 테이블은 NULL 한 줄로 옵니다.
        if row["column_name"] is not None:
            table.columns.append(
                DatasourceColumn(
                    name=row["column_name"],
                    data_type=row["data_type"] or "",
                    description=row["column_description"] or "",
                )
            )
    return list(tables.values())


def _guard_duplicate_name(name: str, except_id: UUID | None = None) -> None:
    """@raises ApiException 409 — 이름이 주소이므로 중복은 저장 시점에 막습니다.

    UNIQUE 제약에 맡기면 psycopg의 UniqueViolation이 500으로 나가고, 화면에는
    무엇이 잘못됐는지 알 수 없는 메시지가 뜹니다.
    """
    if except_id is None:
        row = db.one("SELECT id FROM datasource WHERE lower(name) = lower(%s)", name)
    else:
        row = db.one(
            "SELECT id FROM datasource WHERE lower(name) = lower(%s) AND id <> %s",
            name,
            except_id,
        )
    if row:
        raise ApiException.conflict(f"a datasource named {name!r} already exists")


def _row(row: dict[str, Any]) -> Datasource:
    return Datasource(
        id=row["id"],
        name=row["name"],
        description=row["description"],
        driver=Driver(row["driver"]),
        host=row["host"],
        port=row["port"],
        db_name=row["db_name"],
        db_schema=row["db_schema"],
        username=row["username"],
        synced_at=row["synced_at"].isoformat() if row["synced_at"] else None,
        table_count=row["table_count"],
    )


# ── 동기화 ─────────────────────────────────────────────────────────────────

def sync(datasource_id: UUID) -> SyncResult:
    """대상 DB의 스키마를 읽어 우리 DB에 다시 적습니다.

    @raises ApiException 502 — 대상 DB에 닿지 못했을 때. 우리 잘못이 아니라
    저쪽이 안 되는 것이므로 502이고, 메시지에 드라이버가 말한 이유를 그대로
    싣습니다. "동기화 실패"만으로는 호스트 오타인지 권한인지 알 수 없습니다.
    """
    row = db.one("SELECT * FROM datasource WHERE id = %s", datasource_id)
    if row is None:
        raise ApiException.not_found(f"no datasource with id {datasource_id}")

    driver = Driver(row["driver"])
    if driver is Driver.MYSQL:
        tables = _read_mysql(row)
    else:
        tables = _read_postgresql(row)

    return _replace_schema(datasource_id, tables)


def _replace_schema(datasource_id: UUID, tables: list[DatasourceTable]) -> SyncResult:
    """읽어온 스키마로 통째로 갈아끼웁니다.

    지운 뒤 다시 넣는 것을 한 트랜잭션 안에서 합니다. 나눠서 하면 삭제와 삽입
    사이에 도구가 호출됐을 때 스키마가 빈 것으로 보이고, 그 사이의 질문은
    "테이블을 찾지 못했습니다"로 답합니다.

    사라진 테이블이 남지 않는 것도 이유입니다. 갱신 방식으로 덮어쓰기만 하면
    대상 DB에서 드롭된 테이블이 계약서에 영원히 남고, 모델은 그것을 조회하는
    SQL을 자신 있게 만들어냅니다.
    """
    # Built first, inserted in two statements. A statement per column is a round trip
    # per column, and a real schema is not small: the first database tried here had
    # 371 tables and 4,330 columns, and that loop took 20 seconds — long enough that
    # the button looks broken. executemany pipelines them into one exchange.
    table_rows = []
    column_rows = []
    for table in tables:
        table_id = uuid4()
        table_rows.append((table_id, datasource_id, table.name, table.description))
        for ordinal, column in enumerate(table.columns):
            column_rows.append(
                (table_id, column.name, column.data_type, column.description, ordinal)
            )

    with db.connection() as conn, conn.cursor() as cursor:
        cursor.execute(
            "DELETE FROM datasource_table WHERE datasource_id = %s", (datasource_id,)
        )
        if table_rows:
            cursor.executemany(
                "INSERT INTO datasource_table (id, datasource_id, name, description)"
                " VALUES (%s, %s, %s, %s)",
                table_rows,
            )
        if column_rows:
            cursor.executemany(
                "INSERT INTO datasource_column"
                " (table_id, name, data_type, description, ordinal)"
                " VALUES (%s, %s, %s, %s, %s)",
                column_rows,
            )
        cursor.execute(
            "UPDATE datasource SET synced_at = now(), updated_at = now()"
            " WHERE id = %s RETURNING synced_at",
            (datasource_id,),
        )
        synced = cursor.fetchone()

    column_count = len(column_rows)

    log.info(
        "datasource %s synced: %d tables, %d columns",
        datasource_id,
        len(tables),
        column_count,
    )
    return SyncResult(
        table_count=len(tables),
        column_count=column_count,
        synced_at=synced[0].isoformat(),
    )


def _read_mysql(row: dict[str, Any]) -> list[DatasourceTable]:
    """MySQL/MariaDB의 information_schema를 읽습니다.

    코멘트가 여기서는 information_schema에 그대로 있습니다 — TABLE_COMMENT와
    COLUMN_COMMENT. 프루너가 검색하는 본문이 그것이라, 코멘트 없는 스키마는
    등록해봐야 이름으로만 걸립니다.

    COLUMN_TYPE을 쓰는 것은 DATA_TYPE과 달리 길이와 enum 값까지 들어 있기
    때문입니다 — `varchar(36)`과 `varchar`의 차이입니다.
    """
    try:
        import pymysql
    except ImportError as error:  # pragma: no cover - 의존성 누락은 배포 문제
        raise ApiException.bad_request(
            "the MySQL driver is not installed on the server (pip install PyMySQL)"
        ) from error

    try:
        connection = pymysql.connect(
            host=row["host"],
            port=row["port"],
            user=row["username"],
            password=row["password"],
            database=row["db_name"],
            connect_timeout=CONNECT_TIMEOUT,
            read_timeout=30,
            charset="utf8mb4",
        )
    except Exception as error:
        raise _unreachable(row, error) from error

    try:
        with connection.cursor() as cursor:
            cursor.execute(
                "SELECT TABLE_NAME, TABLE_COMMENT FROM information_schema.TABLES"
                " WHERE TABLE_SCHEMA = %s AND TABLE_TYPE IN ('BASE TABLE', 'VIEW')"
                " ORDER BY TABLE_NAME",
                (row["db_name"],),
            )
            tables = {
                _text(name): DatasourceTable(
                    name=_text(name), description=_text(comment), columns=[]
                )
                for name, comment in cursor.fetchall()
            }

            cursor.execute(
                "SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, COLUMN_COMMENT"
                "  FROM information_schema.COLUMNS"
                " WHERE TABLE_SCHEMA = %s"
                " ORDER BY TABLE_NAME, ORDINAL_POSITION",
                (row["db_name"],),
            )
            for table_name, column, column_type, comment in cursor.fetchall():
                # 위 질의가 걸러낸 목록에 없는 테이블의 컬럼은 버립니다 — 없는
                # 테이블에 컬럼을 붙일 수는 없습니다.
                table = tables.get(_text(table_name))
                if table is not None:
                    table.columns.append(
                        DatasourceColumn(
                            name=_text(column),
                            data_type=_text(column_type),
                            description=_text(comment),
                        )
                    )
    finally:
        connection.close()

    return list(tables.values())


def _read_postgresql(row: dict[str, Any]) -> list[DatasourceTable]:
    """PostgreSQL의 카탈로그를 읽습니다.

    information_schema가 아니라 pg_catalog를 쓰는 이유는 코멘트입니다.
    PostgreSQL은 코멘트를 pg_description에 따로 두고 information_schema로는
    꺼낼 수 없습니다 — 표준에 없는 것이라서입니다. 코멘트 없이 가져오면
    프루너가 검색할 본문이 이름밖에 남지 않습니다.
    """
    import psycopg
    from psycopg.conninfo import make_conninfo

    schema = row["db_schema"] or "public"
    conninfo = make_conninfo(
        host=row["host"],
        port=row["port"],
        user=row["username"] or None,
        password=row["password"] or None,
        dbname=row["db_name"],
        connect_timeout=CONNECT_TIMEOUT,
    )

    try:
        connection = psycopg.connect(conninfo)
    except Exception as error:
        raise _unreachable(row, error) from error

    try:
        with connection.cursor() as cursor:
            # relkind: r=테이블, v=뷰, m=구체화 뷰, p=파티션 부모, f=외부 테이블.
            cursor.execute(
                """
                SELECT c.relname, coalesce(obj_description(c.oid, 'pg_class'), '')
                  FROM pg_class c
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = %s AND c.relkind IN ('r', 'v', 'm', 'p', 'f')
                 ORDER BY c.relname
                """,
                (schema,),
            )
            tables = {
                name: DatasourceTable(name=name, description=comment, columns=[])
                for name, comment in cursor.fetchall()
            }

            cursor.execute(
                """
                SELECT c.relname, a.attname,
                       format_type(a.atttypid, a.atttypmod),
                       coalesce(col_description(c.oid, a.attnum), '')
                  FROM pg_class c
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                  JOIN pg_attribute a ON a.attrelid = c.oid
                 WHERE n.nspname = %s AND c.relkind IN ('r', 'v', 'm', 'p', 'f')
                   -- attnum > 0은 시스템 컬럼(ctid, xmin 등)을 뺍니다. 드롭된
                   -- 컬럼은 행이 남아 있으므로 attisdropped도 함께 봅니다.
                   AND a.attnum > 0 AND NOT a.attisdropped
                 ORDER BY c.relname, a.attnum
                """,
                (schema,),
            )
            for table_name, column, data_type, comment in cursor.fetchall():
                table = tables.get(table_name)
                if table is not None:
                    table.columns.append(
                        DatasourceColumn(
                            name=column,
                            data_type=_text(data_type),
                            description=_text(comment),
                        )
                    )
    finally:
        connection.close()

    return list(tables.values())


def _unreachable(row: dict[str, Any], error: Exception) -> ApiException:
    """드라이버가 말한 첫 줄을 그대로 싣습니다.

    뒤에 붙는 스택과 힌트는 화면에서 읽을 수 없을 만큼 길고, 실제 원인은 언제나
    첫 줄에 있습니다 — "Access denied for user", "Can't connect to MySQL server".
    """
    reason = str(error).strip().splitlines()
    return ApiException.bad_gateway(
        f"could not read the schema of {row['name']} "
        f"({row['host']}:{row['port']}/{row['db_name']}): "
        f"{reason[0] if reason else type(error).__name__}"
    )


def _text(value: Any) -> str:
    """바이트로 오는 이름·코멘트를 문자열로.

    PyMySQL은 콜레이션에 따라 bytes를 돌려줄 때가 있고, 그것이 그대로 pydantic에
    들어가면 검증 오류로 동기화 전체가 죽습니다.
    """
    if isinstance(value, bytes):
        return value.decode("utf-8", errors="replace")
    return str(value) if value is not None else ""
