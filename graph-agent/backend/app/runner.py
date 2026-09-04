"""Runs an agent by executing the very source its code tab shows.

There is no second path that builds a graph from the spec directly. Two paths to one
behaviour meant the exported file and the studio could drift, and in the Java server
they twice did; now there is only the file.

The generated module is reached by attribute lookup rather than through a base class it
would have to import, so the exported file still runs standalone with nothing but its
own PEP 723 dependencies.
"""

import logging
import sys
import traceback
import types
from typing import Any

from . import source
from .callbacks import ModelCallLogger
from .errors import CodeCompilationException, GraphBuildException
from .models import (
    AgentSpec,
    GraphEdge,
    GraphNode,
    GraphType,
    GraphView,
    SourceSpec,
    Step,
)

log = logging.getLogger(__name__)

# The function every generated agent exposes; see templates/graph_*.py.template.
ENTRY_POINT = "build_graph"

# LangGraph's own sentinels, renamed to what the flow tab draws.
SENTINELS = {"__start__": "__START__", "__end__": "__END__"}

# Keyed by source digest, not by agent id: a compiled graph owns its own checkpointer,
# so reusing the instance is what keeps a thread's history alive between turns. Editing
# the code deliberately starts a fresh conversation.
_modules: dict[str, types.ModuleType] = {}
_graphs: dict[str, Any] = {}


def load(src: source.Source) -> types.ModuleType:
    """Executes the agent's source and hands back the resulting module.

    A SyntaxError is the user's own file failing to parse, which the editor shows against
    its lines. Anything raised while the module body runs is a file that parses but does
    not stand up, which is a different message and a different fix.
    """
    cached = _modules.get(src.digest)
    if cached is not None:
        return cached

    module = types.ModuleType(f"agent_{src.digest[:12]}")
    module.__file__ = f"<agent {src.module_name}.py>"
    try:
        compiled = compile(src.code, module.__file__, "exec")
    except SyntaxError as error:
        raise CodeCompilationException(_syntax_errors(error)) from error

    # Registered before execution because a dataclass or pydantic model defined in the
    # file resolves its own module by name, and both look that up in sys.modules.
    sys.modules[module.__name__] = module
    try:
        exec(compiled, module.__dict__)
    except Exception as error:
        del sys.modules[module.__name__]
        raise GraphBuildException(
            f"the agent's source raised while loading: {type(error).__name__}: {error}"
        ) from error

    _modules[src.digest] = module
    return module


def build(spec: AgentSpec):
    """The compiled graph this agent runs, built once per revision of its source."""
    src = source.of(spec)

    # Which file is about to run, said before anything can fail while running it.
    # `edited` is the half that matters: an agent behaving unlike its spec form is
    # usually one whose source was edited, and nothing else in the log says so.
    log.info(
        "agent source: %s, %d lines, %s (%s)",
        spec.name,
        len(src.code.splitlines()),
        "edited" if src.edited else "generated",
        src.digest[:12],
    )

    cached = _graphs.get(src.digest)
    if cached is not None:
        return cached

    module = load(src)
    entry = getattr(module, ENTRY_POINT, None)
    if not callable(entry):
        raise GraphBuildException(
            f"the agent's source has no `{ENTRY_POINT}(model)`; "
            "the studio calls that function to run it"
        )

    model = _chat_model(module)
    try:
        graph = entry(model)
    except Exception as error:
        log.warning("agent %s failed to build\n%s", spec.id, traceback.format_exc())
        raise GraphBuildException(
            f"the agent's {ENTRY_POINT} raised: {type(error).__name__}: {error}"
        ) from error

    _graphs[src.digest] = graph
    return graph


def run_config(spec: AgentSpec, thread_id: str) -> dict[str, Any]:
    """The config the agent's own file says a run should have.

    Taken from the module rather than rebuilt here, so a user who raises the recursion
    limit in their edit gets the limit they wrote.
    """
    module = load(source.of(spec))
    builder = getattr(module, "run_config", None)
    config = (
        builder(thread_id)
        if callable(builder)
        else {"configurable": {"thread_id": thread_id}}
    )

    # Added here rather than onto the model, because LangGraph passes this config down
    # into every node: attaching it to the model logs the model's calls and nothing
    # else, and the tools are half of what a run does. The generated file does not carry
    # it — a standalone `uv run` should stay quiet unless its author asks otherwise.
    listeners = list(config.get("callbacks") or [])
    return {**config, "callbacks": [*listeners, ModelCallLogger()]}


def graph_view(spec: AgentSpec) -> GraphView:
    """The graph the agent will actually run, read off its compiled source.

    Read from the built graph rather than parsed out of the text: the linear template
    builds its nodes in a loop, so the node names are not literals in the file at all,
    while the compiled graph already holds exactly what LangGraph will execute.
    """
    src = source.of(spec)
    compiled = build(spec)
    drawn = compiled.get_graph()

    edges = [
        GraphEdge(
            source=_rename(edge.source),
            target=_rename(edge.target),
            label=_label(edge),
        )
        for edge in drawn.edges
    ]

    # Every node an edge can touch. START and END are sentinels rather than ordinary
    # registered nodes, so taking only the registered ones would leave the edges into
    # and out of the graph with nothing to attach to, and they would not be drawn.
    ids = {_rename(node_id) for node_id in drawn.nodes}
    for edge in edges:
        ids.add(edge.source)
        ids.add(edge.target)

    return GraphView(
        edited=src.edited,
        # Sorted so the same graph always lays out the same way.
        nodes=[GraphNode(id=node_id) for node_id in sorted(ids)],
        edges=edges,
        tools=tool_names(spec),
    )


def tool_names(spec: AgentSpec) -> list[str]:
    """What the source's own `tools()` really returns.

    This is what makes a tool deleted from the code disappear from the studio: the
    spec's tool list does not move when someone edits the file, so anything derived from
    it would keep showing a tool that no longer runs.
    """
    module = load(source.of(spec))
    registered = getattr(module, "tools", None)
    if not callable(registered):
        # An edited file may drop the function entirely; it just has no tools.
        return []
    try:
        return [tool.name for tool in registered()]
    except Exception as error:
        raise GraphBuildException(
            f"the agent's tools() could not be read: {type(error).__name__}: {error}"
        ) from error


def source_spec(spec: AgentSpec) -> SourceSpec:
    """Reads a spec back out of an agent's source, for applying an edit onto the form.

    The values wanted are the constants the template declares. Reading them off a loaded
    module costs nothing beyond the load the flow tab already pays for, while a parser
    would have to re-implement Python string literals.
    """
    module = load(source.of(spec))
    prompts = getattr(module, "STEP_PROMPTS", None)

    # The linear template is the only one that declares STEP_PROMPTS, so its presence
    # identifies the graph type more reliably than inspecting the nodes.
    is_linear = isinstance(prompts, list)

    return SourceSpec(
        model=_constant(module, "MODEL", str),
        system_prompt=_constant(module, "SYSTEM_PROMPT", str),
        max_iterations=_constant(module, "MAX_ITERATIONS", int),
        tools=tool_names(spec),
        graph_type=GraphType.LINEAR if is_linear else GraphType.REACT,
        steps=_steps(prompts, spec) if is_linear else [],
    )


def _steps(prompts: list, spec: AgentSpec) -> list[Step]:
    """Pairs each recovered prompt with a name.

    Names are not in the file, so the current spec's name at the same position is reused
    where there is one — renaming steps is common, and losing those names on every apply
    would be its own annoyance.
    """
    steps = []
    for index, prompt in enumerate(prompts):
        existing = spec.steps[index].name if index < len(spec.steps) else ""
        steps.append(
            Step(name=existing.strip() or f"단계 {index + 1}", prompt=str(prompt))
        )
    return steps


def _chat_model(module: types.ModuleType):
    """The model the agent's own file builds, so studio and standalone runs agree."""
    factory = getattr(module, "chat_model", None)
    if not callable(factory):
        raise GraphBuildException(
            "the agent's source has no `chat_model()`; the studio calls it to build the "
            "model it passes to build_graph"
        )
    try:
        return factory()
    except Exception as error:
        raise GraphBuildException(
            f"the agent's chat_model() raised: {type(error).__name__}: {error}"
        ) from error


def _constant(module: types.ModuleType, name: str, expected: type):
    """None when an edited file no longer declares the constant, which is allowed."""
    value = getattr(module, name, None)
    return value if isinstance(value, expected) else None


def _label(edge: Any) -> str | None:
    """The value the condition returned to take this branch; None when unconditional.

    LangGraph fills `data` only when the branch's key differs from the node it leads to,
    and a path map like `{"tools": "tools", END: END}` has no such difference — so every
    conditional edge in the generated templates arrives with `data=None`. Falling back to
    the target recovers the key, which is exactly what was mapped. Without this the flow
    tab draws a conditional branch as an ordinary edge, because `label` being null is how
    it tells the two apart.
    """
    if edge.data:
        return _rename(edge.data)
    return _rename(edge.target) if edge.conditional else None


def _rename(node_id: str) -> str:
    return SENTINELS.get(node_id, node_id)


def _syntax_errors(error: SyntaxError) -> list[str]:
    """One message per problem, shaped like a compiler's so the editor can show it."""
    where = f"line {error.lineno}" if error.lineno else "unknown line"
    text = (error.text or "").rstrip()
    return [f"{where}: {error.msg}"] + ([f"    {text}"] if text else [])
