"""The built-in tools the editor offers as checkboxes."""

from fastapi import APIRouter

from .. import tools
from ..models import ToolInfo

router = APIRouter(prefix="/api/tools", tags=["tools"])


@router.get("")
def list_tools() -> list[ToolInfo]:
    return [ToolInfo(**tool) for tool in tools.describe()]
