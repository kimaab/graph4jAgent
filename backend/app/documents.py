"""Reference material an agent can search.

Uploading extracts the text once, a page at a time, because a search answers with a page
number. The `document_search` tool reads it back out of the database and never re-parses
a PDF, which is also why an exported agent carries no PDF library.

Large uploads are handled on disk, not on the heap: reading a 300MB file into memory and
then parsing it there trades a clean rejection for an OutOfMemory that takes the whole
process with it.
"""

import logging
import re
from pathlib import Path
from uuid import UUID, uuid4

from pypdf import PdfReader
from pypdf.errors import PdfReadError

from . import db
from .errors import ApiException
from .models import AgentDocument

log = logging.getLogger(__name__)

# Guards against a pathological page dominating the stored corpus.
MAX_CHARS_PER_PAGE = 20_000

# The extracted text is held in memory before it is stored, so page count is what bounds
# that. At the per-page cap above this is roughly 100MB of text — already far more than
# any agent can read.
MAX_PAGES = 5_000

# How many page rows go to the driver at once, so a thousand-page document does not
# build one enormous statement.
PAGE_BATCH = 200


def extract_pages(pdf: Path) -> list[str]:
    """Page text in page order, index 0 being page 1.

    A page with no extractable text yields an empty string rather than disappearing, so
    page numbers stay aligned with what the reader sees.

    @raises ApiException 400 when the bytes are not a PDF this library can open
    """
    try:
        reader = PdfReader(str(pdf))
        if reader.is_encrypted:
            # An empty user password is common and decrypts silently; anything else
            # cannot be read.
            try:
                if reader.decrypt("") == 0:
                    raise ApiException.bad_request(
                        "the PDF is encrypted, so its text cannot be read"
                    )
            except NotImplementedError as error:
                raise ApiException.bad_request(
                    f"the PDF uses an encryption this reader does not support: {error}"
                ) from error

        total = len(reader.pages)
        if total > MAX_PAGES:
            raise ApiException.bad_request(
                f"the PDF has {total} pages; the limit is {MAX_PAGES}. "
                "Split it and upload the parts separately"
            )

        return [_trim(page.extract_text() or "") for page in reader.pages]
    except PdfReadError as error:
        raise ApiException.bad_request(f"could not read the PDF: {error}") from error


def insert(
    agent_id: UUID, filename: str, content_type: str, byte_size: int, pages: list[str]
) -> AgentDocument:
    """Metadata and pages land together or not at all."""
    document_id = uuid4()

    with db.connection() as conn:
        conn.execute(
            """
            INSERT INTO agent_document
                (id, agent_id, filename, content_type, byte_size, page_count)
            VALUES (%s, %s, %s, %s, %s, %s)
            """,
            (document_id, agent_id, filename, content_type, byte_size, len(pages)),
        )

        # Numbered by position, not by looking the text up: two identical pages (two
        # blank ones, say) would both resolve to the first one's number and collide on
        # the unique key. An empty page still gets a row so page numbers keep matching
        # the file.
        rows = [(document_id, number, text) for number, text in enumerate(pages, start=1)]
        with conn.cursor() as cursor:
            for start in range(0, len(rows), PAGE_BATCH):
                cursor.executemany(
                    """
                    INSERT INTO agent_document_page (document_id, page_number, content)
                    VALUES (%s, %s, %s)
                    """,
                    rows[start : start + PAGE_BATCH],
                )

    stored = find_by_id(document_id)
    if stored is None:
        raise RuntimeError(f"document vanished right after insert: {document_id}")
    return stored


def find_by_agent(agent_id: UUID) -> list[AgentDocument]:
    return [
        _row(r)
        for r in db.query(
            "SELECT * FROM agent_document WHERE agent_id = %s ORDER BY uploaded_at DESC",
            agent_id,
        )
    ]


def find_by_id(document_id: UUID) -> AgentDocument | None:
    row = db.one("SELECT * FROM agent_document WHERE id = %s", document_id)
    return _row(row) if row else None


def delete(agent_id: UUID, document_id: UUID) -> bool:
    """Pages go with it: the foreign key cascades."""
    return (
        db.execute(
            "DELETE FROM agent_document WHERE id = %s AND agent_id = %s",
            document_id,
            agent_id,
        )
        > 0
    )


def _trim(text: str) -> str:
    cleaned = re.sub(r"[ \t]+", " ", text).strip()
    return cleaned[:MAX_CHARS_PER_PAGE]


def _row(row: dict) -> AgentDocument:
    return AgentDocument(
        id=row["id"],
        agent_id=row["agent_id"],
        filename=row["filename"],
        content_type=row["content_type"],
        byte_size=row["byte_size"],
        page_count=row["page_count"],
        # Stored UTC; the browser renders it in the viewer's own zone.
        uploaded_at=row["uploaded_at"].isoformat(),
    )
