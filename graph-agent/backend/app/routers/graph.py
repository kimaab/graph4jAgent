"""What the flow tab draws, and what the 정의 tab applies an edit back onto."""

from uuid import UUID

from fastapi import APIRouter

from .. import runner
from ..models import GraphView, SourceSpec
from .agents import require

router = APIRouter(prefix="/api/agents", tags=["graph"])


@router.get("/{agent_id}/graph")
def graph(agent_id: UUID) -> GraphView:
    return runner.graph_view(require(agent_id))


@router.get("/{agent_id}/source-spec")
def source_spec(agent_id: UUID) -> SourceSpec:
    """The spec as the agent's source actually defines it.

    Answers from the source whether or not it has been edited; for an unedited agent it
    simply matches the spec.
    """
    return runner.source_spec(require(agent_id))
