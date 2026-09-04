"""CRUD over the agent spec.

No service layer: this is straight persistence, and the behaviour that would justify one
lives in the runner.
"""

from uuid import UUID

from fastapi import APIRouter, Response

from .. import repository, tools
from ..errors import ApiException
from ..models import AgentSpec, AgentSpecInput

router = APIRouter(prefix="/api/agents", tags=["agents"])


@router.get("")
def list_agents() -> list[AgentSpec]:
    return repository.find_all()


@router.get("/{agent_id}")
def get_agent(agent_id: UUID) -> AgentSpec:
    return require(agent_id)


@router.post("", status_code=201)
def create_agent(spec: AgentSpecInput) -> AgentSpec:
    """The client-supplied id is ignored; the server always assigns one."""
    tools.resolve(spec.tools)
    return repository.insert(spec)


@router.put("/{agent_id}")
def update_agent(agent_id: UUID, spec: AgentSpecInput) -> AgentSpec:
    tools.resolve(spec.tools)
    saved = repository.update(agent_id, spec)
    if saved is None:
        raise ApiException.not_found(f"agent not found: {agent_id}")
    return saved


@router.delete("/{agent_id}", status_code=204, response_class=Response)
def delete_agent(agent_id: UUID) -> Response:
    if not repository.delete_by_id(agent_id):
        raise ApiException.not_found(f"agent not found: {agent_id}")
    return Response(status_code=204)


def require(agent_id: UUID) -> AgentSpec:
    """The spec, or a 404 naming the id. Every per-agent route starts here."""
    spec = repository.find_by_id(agent_id)
    if spec is None:
        raise ApiException.not_found(f"agent not found: {agent_id}")
    return spec
