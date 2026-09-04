"""PDF upload and removal.

The file is streamed to a spool file this module owns rather than read into memory:
`await file.read()` on a 300MB upload is an OOM that takes the process with it. The
spool lives under APP_DATA_DIR because that is the only directory this app may write —
tempfile's default would point at a path the app user cannot touch.
"""

import logging
from pathlib import Path
from uuid import UUID, uuid4

from fastapi import APIRouter, File, Response, UploadFile

from .. import config, documents
from ..errors import ApiException
from ..models import AgentDocument
from .agents import require

log = logging.getLogger(__name__)
router = APIRouter(prefix="/api/agents/{agent_id}/documents", tags=["documents"])

# How much is copied per iteration while spooling.
CHUNK = 1024 * 1024


@router.get("")
def list_documents(agent_id: UUID) -> list[AgentDocument]:
    require(agent_id)
    return documents.find_by_agent(agent_id)


@router.post("", status_code=201)
async def upload(agent_id: UUID, file: UploadFile = File(...)) -> AgentDocument:
    require(agent_id)

    filename = file.filename or ""
    if not filename.lower().endswith(".pdf"):
        raise ApiException.bad_request("only PDF files are supported for now")

    spooled, size = await _spool(file)
    try:
        if size == 0:
            raise ApiException.bad_request("the uploaded file is empty")

        pages = documents.extract_pages(spooled)
        if all(not page.strip() for page in pages):
            # Almost always a scan: images of text, with no text layer to extract.
            raise ApiException.bad_request(
                "no text could be extracted; a scanned PDF needs OCR before it can be "
                "searched"
            )

        return documents.insert(
            agent_id,
            filename,
            file.content_type or "application/pdf",
            size,
            pages,
        )
    finally:
        spooled.unlink(missing_ok=True)


@router.delete("/{document_id}", status_code=204, response_class=Response)
def delete(agent_id: UUID, document_id: UUID) -> Response:
    require(agent_id)
    if not documents.delete(agent_id, document_id):
        raise ApiException.not_found(f"document not found: {document_id}")
    return Response(status_code=204)


async def _spool(file: UploadFile) -> tuple[Path, int]:
    """Copies the upload to disk a chunk at a time, and stops at the limit.

    The size is checked as it goes rather than from a Content-Length header: the header
    is the client's claim, and the point of the limit is what actually arrives.
    """
    config.UPLOAD_SPOOL_DIR.mkdir(parents=True, exist_ok=True)
    spooled = config.UPLOAD_SPOOL_DIR / f"upload-{uuid4().hex}.pdf"

    size = 0
    try:
        with spooled.open("wb") as out:
            while chunk := await file.read(CHUNK):
                size += len(chunk)
                if size > config.MAX_UPLOAD_BYTES:
                    raise ApiException(
                        413,
                        "the file is larger than the "
                        f"{config.MAX_UPLOAD_BYTES // (1024 * 1024)}MB upload limit",
                    )
                out.write(chunk)
    except Exception:
        spooled.unlink(missing_ok=True)
        raise
    return spooled, size
