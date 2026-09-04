"""Runs an agent and streams what happens as Server-Sent Events.

Event names are `token`, `tool_call`, `tool_result`, `done` and `error`. Conversation
history is keyed by `thread_id` through the graph's checkpointer.

The generator is synchronous on purpose: LangGraph's nodes call the model synchronously,
and Starlette runs a sync stream in a worker thread, so nothing blocks the event loop and
there is no queue to plumb between the two.
"""

import json
import logging
import traceback
from typing import Any, Iterator
from uuid import UUID

from fastapi import APIRouter
from fastapi.responses import StreamingResponse
from langchain_core.messages import AIMessage, AIMessageChunk, ToolMessage

from .. import runner
from ..models import AgentSpec, RunRequest
from .agents import require

log = logging.getLogger(__name__)
router = APIRouter(prefix="/api/agents", tags=["run"])


@router.post("/{agent_id}/run")
def run(agent_id: UUID, request: RunRequest) -> StreamingResponse:
    spec = require(agent_id)

    # Built before the response is handed back: source that will not load should fail as
    # a 400 on this request, not as an error event on a stream the client has accepted.
    graph = runner.build(spec)
    config = runner.run_config(spec, request.thread_id)

    return StreamingResponse(
        _events(graph, config, spec, request),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            # Without this a reverse proxy may buffer the whole run and deliver it at
            # the end, which looks exactly like a hung agent.
            "X-Accel-Buffering": "no",
        },
    )


def _events(
    graph: Any, config: dict[str, Any], spec: AgentSpec, request: RunRequest
) -> Iterator[str]:
    from langchain_core.messages import HumanMessage

    # Everything already in the thread belongs to earlier turns; replaying its tool
    # activity would redraw stale blocks in the client on every message.
    cursor = _history_length(graph, config)

    try:
        for mode, chunk in graph.stream(
            {"messages": [HumanMessage(request.message)]},
            config,
            stream_mode=["messages", "values"],
        ):
            if mode == "messages":
                message, _metadata = chunk
                # Only the model's own chunks are tokens. Tool output arrives here too,
                # and emitting it would print the raw tool result into the answer.
                if isinstance(message, AIMessageChunk) and message.content:
                    yield _frame("token", {"text": _text(message.content)})
                continue

            if mode == "values":
                for event in _tool_activity(chunk.get("messages", []), cursor):
                    yield event
                cursor = len(chunk.get("messages", []))

        yield _frame("done", {"thread_id": request.thread_id})
    except Exception as error:
        log.warning(
            "agent %s failed on thread %s\n%s",
            spec.id,
            request.thread_id,
            traceback.format_exc(),
        )
        yield _frame("error", {"detail": f"{type(error).__name__}: {error}"})


def _tool_activity(messages: list, start: int) -> Iterator[str]:
    """Reports tool activity for messages after `start`.

    Read off the state rather than off the node name: a renamed or re-ordered node
    cannot silently stop reporting. Every state update carries the whole history, so the
    cursor is what keeps each event to a single emission.
    """
    for message in messages[start:]:
        if isinstance(message, AIMessage) and message.tool_calls:
            for call in message.tool_calls:
                yield _frame(
                    "tool_call",
                    {
                        "id": call.get("id") or "",
                        "name": call.get("name") or "",
                        # The client shows this verbatim, so it is a string, not an object.
                        "arguments": json.dumps(
                            call.get("args") or {}, ensure_ascii=False
                        ),
                    },
                )
        elif isinstance(message, ToolMessage):
            yield _frame(
                "tool_result",
                {
                    "id": message.tool_call_id or "",
                    "name": message.name or "",
                    "result": _text(message.content),
                },
            )


def _history_length(graph: Any, config: dict[str, Any]) -> int:
    """How many messages this thread already holds, or zero for a new one."""
    try:
        state = graph.get_state(config)
    except Exception:
        return 0
    values = getattr(state, "values", None) or {}
    return len(values.get("messages", []))


def _text(content: Any) -> str:
    """Flattens the content of a message.

    A model may answer with a list of typed blocks rather than a bare string, and the
    client's token events are plain text.
    """
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts = []
        for block in content:
            if isinstance(block, str):
                parts.append(block)
            elif isinstance(block, dict) and isinstance(block.get("text"), str):
                parts.append(block["text"])
        return "".join(parts)
    return str(content)


def _frame(event: str, data: dict[str, Any]) -> str:
    """One SSE frame. The blank line at the end is what ends the event."""
    return f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"
