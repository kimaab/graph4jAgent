"""The agent's source.

Reads fall back to generating from the spec until someone edits the file; from then on
the stored edit is what this returns and what the runner executes, so the code tab and
the run tab can never drift apart.

The body is plain text with the metadata in headers, so a plain `curl -OJ` still yields
a file you can run. A browser hides every response header that is not exposed, which is
why the CORS config lists these two by name.
"""

from uuid import UUID

from fastapi import APIRouter
from fastapi.responses import PlainTextResponse

from .. import codegen, repository, runner, source
from ..models import AgentSpec, CodeStatus, CodeUpdate
from .agents import require

router = APIRouter(prefix="/api/agents", tags=["code"])

# Says whether the body is the user's edit or a fresh render of the spec.
EDITED_HEADER = "X-Code-Edited"
FILENAME_HEADER = "X-Suggested-Filename"


@router.get("/{agent_id}/code/status")
def status(agent_id: UUID) -> CodeStatus:
    """Just the edited flag, so the 정의 tab can warn that the form no longer decides
    what runs.

    Deliberately not GET /code: that renders the whole file, and generating a few hundred
    lines to answer one boolean is work for nothing.
    """
    require(agent_id)
    return CodeStatus(edited=repository.find_code(agent_id) is not None)


@router.get("/{agent_id}/code", response_class=PlainTextResponse)
def code(agent_id: UUID) -> PlainTextResponse:
    spec = require(agent_id)
    src = source.of(spec)
    return _body(spec, src.code, src.edited)


@router.put("/{agent_id}/code", response_class=PlainTextResponse)
def save(agent_id: UUID, update: CodeUpdate) -> PlainTextResponse:
    """Stores an edit. From here on the spec form no longer decides what runs."""
    spec = require(agent_id)
    # Rejects a file that will not parse before it is stored, so a saved agent is always
    # one the run tab can at least load.
    runner.load(source.Source(update.code, codegen.module_name_for(spec.name), True,
                              codegen.digest(update.code)))
    repository.save_code(agent_id, update.code)
    return _body(spec, update.code, True)


@router.post("/{agent_id}/code/regenerate", response_class=PlainTextResponse)
def regenerate(agent_id: UUID) -> PlainTextResponse:
    """Throws the edit away and renders the spec again."""
    spec = require(agent_id)
    repository.clear_code(agent_id)
    return _body(spec, codegen.generate(spec), False)


def _body(spec: AgentSpec, code: str, edited: bool) -> PlainTextResponse:
    return PlainTextResponse(
        content=code,
        media_type="text/plain; charset=utf-8",
        headers={
            FILENAME_HEADER: codegen.file_name_for(spec),
            EDITED_HEADER: str(edited).lower(),
        },
    )
