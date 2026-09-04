"""The one answer to "what is this agent's source?".

Shared by the code endpoint, the runner and the flow tab, so the file a user edits is
provably the file that runs.
"""

from dataclasses import dataclass

from . import codegen, repository
from .models import AgentSpec


@dataclass(frozen=True)
class Source:
    code: str
    module_name: str
    # True once someone has saved an edit, meaning the spec form no longer decides
    # what this agent does.
    edited: bool
    # Identifies this revision, so a cache can tell one from the next.
    digest: str


def of(spec: AgentSpec) -> Source:
    stored = repository.find_code(spec.id)
    code = stored if stored is not None else codegen.generate(spec)
    return Source(
        code=code,
        module_name=codegen.module_name_for(spec.name),
        edited=stored is not None,
        digest=codegen.digest(code),
    )
