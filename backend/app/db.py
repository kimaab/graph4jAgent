"""PostgreSQL access.

One connection pool for the process, opened at startup so a bad DB_URL fails the boot
with a message rather than the first request with a 500. The schema is the Java
server's, unchanged: both backends are meant to read the same database.
"""

import logging
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Iterator

import psycopg
from psycopg import Connection
from psycopg.conninfo import conninfo_to_dict
from psycopg.rows import dict_row
from psycopg_pool import ConnectionPool

from . import config

log = logging.getLogger(__name__)

SCHEMA = Path(__file__).resolve().parent / "schema.sql"

_pool: ConnectionPool | None = None


def describe_url() -> str:
    """What DB_URL actually parsed to, with the password reduced to present/absent.

    Printed at startup because this is the fact every connection failure turns on, and
    the one nobody can recover afterwards: on the platform there is no shell to echo the
    variable in. A password lost to a shell's quoting rules — an unquoted `#` starts a
    comment, and the value silently ends there — looks exactly like a wrong password,
    and only this line tells the two apart.
    """
    try:
        parsed = conninfo_to_dict(config.DB_URL)
    except Exception as error:
        return f"unparseable ({type(error).__name__}: {error})"

    password = parsed.get("password")
    return (
        f"{parsed.get('user', '?')}@{parsed.get('host', '?')}:{parsed.get('port', '?')}"
        f"/{parsed.get('dbname', '?')} "
        f"password={'set, ' + str(len(password)) + ' chars' if password else 'MISSING'}"
    )


def start() -> None:
    """Opens the pool and applies the schema. Raises when DB_URL is absent or wrong."""
    global _pool
    if not config.DB_URL:
        raise RuntimeError(
            "DB_URL is not set. Declare it on the deploy screen: the studio keeps agent "
            "specs, document text and edited source in PostgreSQL."
        )

    print(f"connecting to PostgreSQL as {describe_url()}", flush=True)

    # One plain connection before the pool. The pool retries in the background and then
    # fails with `PoolTimeout: pool initialization incomplete`, which names neither the
    # server's reason nor the credentials tried — the real "password authentication
    # failed" only ever appeared as a warning above the traceback.
    try:
        psycopg.connect(config.DB_URL, connect_timeout=10).close()
    except psycopg.OperationalError as error:
        raise RuntimeError(
            f"could not connect to PostgreSQL as {describe_url()}: "
            f"{str(error).strip().splitlines()[0]}"
        ) from error

    _pool = ConnectionPool(config.DB_URL, min_size=1, max_size=8, open=True, timeout=10)
    _pool.wait(timeout=15)

    print("applying schema...", flush=True)
    with connection() as conn:
        conn.execute(SCHEMA.read_text(encoding="utf-8"))
    print("database ready", flush=True)


def stop() -> None:
    global _pool
    if _pool is not None:
        _pool.close()
        _pool = None


@contextmanager
def connection() -> Iterator[Connection]:
    """A pooled connection in its own transaction, committed on a clean exit."""
    if _pool is None:
        raise RuntimeError("the database pool is not open")
    with _pool.connection() as conn:
        yield conn


def query(sql: str, *params: Any) -> list[dict[str, Any]]:
    with connection() as conn, conn.cursor(row_factory=dict_row) as cur:
        cur.execute(sql, params)
        return cur.fetchall()


def one(sql: str, *params: Any) -> dict[str, Any] | None:
    rows = query(sql, *params)
    return rows[0] if rows else None


def execute(sql: str, *params: Any) -> int:
    """@return how many rows the statement touched."""
    with connection() as conn, conn.cursor() as cur:
        cur.execute(sql, params)
        return cur.rowcount
