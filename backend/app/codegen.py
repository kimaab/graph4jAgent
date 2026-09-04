"""Renders a spec as one self-contained Python file.

The generated file pins the same LangGraph and LangChain versions this server runs and
carries the same tool implementations, so an exported agent behaves the way it did in
the studio. Templates live in `templates/` and can be edited without touching this file.

Values are substituted as complete Python literals (`repr`), never spliced inside quotes
the template already opened: a system prompt containing a quote or a backslash would
otherwise generate a file that does not parse.
"""

import hashlib
import re
from pathlib import Path

from . import config, tools
from .models import AgentSpec, GraphType, Step

TEMPLATES = Path(__file__).resolve().parent / "templates"


def generate(spec: AgentSpec) -> str:
    """The agent's source, rendered fresh from its spec."""
    selected = tools.resolve(_distinct(spec.tools))

    return (
        _template("agent.py.template")
        .replace("__AGENT_NAME__", _docstring_safe(spec.name))
        .replace("__MODULE_NAME__", module_name_for(spec.name))
        .replace("__AGENT_ID_LITERAL__", repr(str(spec.id)))
        .replace("__AGENT_ID__", str(spec.id))
        .replace("__BASE_URL__", repr(config.LLM_BASE_URL))
        .replace("__API_KEY__", repr(config.LLM_API_KEY))
        .replace("__MODEL__", repr(spec.model))
        .replace("__DB_URL__", repr(config.DB_URL))
        .replace("__SYSTEM_PROMPT__", repr(spec.system_prompt))
        .replace("__MAX_ITERATIONS__", str(spec.max_iterations))
        # The newline is part of the placeholder so that no extra dependencies leaves no
        # line at all. A blank line would end the PEP 723 block early — see _tool_deps.
        .replace("__TOOL_DEPS__\n", _tool_deps(selected))
        .replace("__GRAPH_BUILDER__", _graph_builder(spec))
        .replace("__TOOL_FUNCTIONS__", _tool_functions(selected))
        .replace("__TOOL_INSTANCES__", ", ".join(t.symbol for t in selected))
    )


def file_name_for(spec: AgentSpec) -> str:
    """The name a client should save the generated source as."""
    return f"{module_name_for(spec.name)}.py"


def digest(code: str) -> str:
    """Identifies a revision of the source, so a cache can key on it without holding
    the whole file."""
    return hashlib.sha256(code.encode("utf-8")).hexdigest()


def module_name_for(name: str) -> str:
    """A legal Python module name derived from the agent name.

    A wholly non-ASCII name has nothing to derive from — "새 에이전트" leaves no
    identifier characters at all — so it falls back rather than emitting a file that
    cannot be imported.
    """
    cleaned = re.sub(r"[^0-9A-Za-z]+", "_", name).strip("_").lower()
    if not cleaned or cleaned[0].isdigit():
        return "agent"
    return cleaned


def _graph_builder(spec: AgentSpec) -> str:
    if spec.graph_type is GraphType.REACT:
        return _template("graph_react.py.template")
    return _template("graph_linear.py.template").replace(
        "__STEP_PROMPTS__", _step_prompts(spec.steps)
    )


def _step_prompts(steps: list[Step]) -> str:
    return ",\n".join(f"    {step.prompt!r}" for step in steps)


def _tool_functions(selected: list[tools.BuiltinTool]) -> str:
    return "\n".join(_template(tool.template) for tool in selected)


def _tool_deps(selected: list[tools.BuiltinTool]) -> str:
    """One PEP 723 dependency line per extra requirement a selected tool asks for.

    Includes its own trailing newline, and returns nothing at all when no tool asks for
    anything. That is not tidiness: a PEP 723 block ends at the first line that does not
    start with `#`, so a blank line left where these would go puts the closing `# ]` and
    `# ///` outside the block. `uv run` then finds no dependencies and the exported file
    fails on its first import — while the studio, which never reads the header, keeps
    working. Tools with no extra dependencies are the common case, so this was the
    default shape of every generated file.
    """
    seen: list[str] = []
    for tool in selected:
        for dependency in tool.dependencies:
            if dependency not in seen:
                seen.append(dependency)
    return "".join(f'#     "{dependency}",\n' for dependency in seen)


def _distinct(names: list[str]) -> list[str]:
    return list(dict.fromkeys(names))


def _docstring_safe(value: str) -> str:
    """Keeps a free-text name from closing the module docstring it sits in."""
    return value.replace('"""', "'''").replace("\r", " ").replace("\n", " ")


def _template(name: str) -> str:
    path = TEMPLATES / name
    if not path.exists():
        raise RuntimeError(f"missing code template: {name}")
    return path.read_text(encoding="utf-8")
