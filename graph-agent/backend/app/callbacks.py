"""Server-side logging of what the model was asked and what it cost.

Records what the run stream cannot show the browser: the prompt as the model actually
received it — system prompt, history and tool results all assembled — plus which model
answered, how long it took, and how many tokens it spent.

The prompt is the whole point of the log. A wrong answer is usually a prompt that was
not what its author pictured: a system prompt that did not survive an edit, a tool
result that arrived empty, history that grew past what anyone intended. None of that is
visible in the reply, and none of it is recoverable once the run is over.
"""

import json
import logging
import time
from typing import Any
from uuid import UUID

from langchain_core.callbacks.base import BaseCallbackHandler

log = logging.getLogger(__name__)

# Per message, not per prompt. Long enough for a system prompt or a tool result to
# arrive whole, short enough that a run against a large history stays readable. The
# count of what was cut is always logged, so a truncated line still says it is one.
MESSAGE_CHARS = 2000


class ModelCallLogger(BaseCallbackHandler):
    """One log line when a model or tool call starts, one when it ends.

    Chat models report through `on_chat_model_start`; `on_llm_start` is for plain
    completion models and never fires for ChatOpenAI. Both end at `on_llm_end`.
    """

    def __init__(self) -> None:
        self._started: dict[UUID, float] = {}

    def on_chat_model_start(
        self, serialized: dict, messages: list, *, run_id: UUID, **kwargs: Any
    ) -> None:
        self._started[run_id] = time.monotonic()
        turn = messages[0] if messages else []
        # One record rather than one per message: Starlette runs each stream in its own
        # worker thread, so two concurrent runs would otherwise interleave line by line
        # and neither prompt could be read back.
        log.info(
            "model call: %s, %d messages\n%s",
            _model_name(serialized),
            len(turn),
            _render(turn),
        )

    def on_llm_end(self, response: Any, *, run_id: UUID, **kwargs: Any) -> None:
        usage = _usage(response)
        if usage:
            log.info(
                "model done in %.2fs: %s prompt + %s completion = %s tokens",
                self._elapsed(run_id),
                usage.get("input", "?"),
                usage.get("output", "?"),
                usage.get("total", "?"),
            )
        else:
            log.info("model done in %.2fs", self._elapsed(run_id))

    def on_llm_error(self, error: BaseException, *, run_id: UUID, **kwargs: Any) -> None:
        log.warning(
            "model failed after %.2fs: %s: %s",
            self._elapsed(run_id),
            type(error).__name__,
            error,
        )

    def on_tool_start(
        self, serialized: dict, input_str: str, *, run_id: UUID, **kwargs: Any
    ) -> None:
        self._started[run_id] = time.monotonic()
        log.info("tool call: %s", (serialized or {}).get("name") or "?")

    def on_tool_end(self, output: Any, *, run_id: UUID, **kwargs: Any) -> None:
        log.info("tool done in %.2fs", self._elapsed(run_id))

    def on_tool_error(self, error: BaseException, *, run_id: UUID, **kwargs: Any) -> None:
        log.warning("tool failed: %s: %s", type(error).__name__, error)

    def _elapsed(self, run_id: UUID) -> float:
        """Seconds since the matching start, popped so the map cannot grow unbounded."""
        started = self._started.pop(run_id, None)
        return time.monotonic() - started if started else 0.0


def _render(messages: list) -> str:
    """The prompt as the model received it, one indented line per message.

    Numbered because position is what a prompt bug usually turns on — a system prompt
    that landed second, history in the wrong order — and that cannot be seen in a bare
    list of contents.
    """
    if not messages:
        return "  (empty prompt)"
    return "\n".join(
        f"  [{index}] {_role(message):<6} | {_body(message)}"
        for index, message in enumerate(messages, start=1)
    )


def _role(message: Any) -> str:
    """system / human / ai / tool, as LangChain names them."""
    return getattr(message, "type", None) or type(message).__name__


def _body(message: Any) -> str:
    """What this message contributes to the prompt, tool calls included.

    An assistant message that only calls a tool has empty content, so rendering content
    alone would print a blank line exactly where the interesting thing happened.
    """
    parts = []

    text = _clip(_flatten(getattr(message, "content", "")))
    if text:
        parts.append(text)

    for call in getattr(message, "tool_calls", None) or []:
        parts.append(f"-> {call.get('name') or '?'}({_args(call.get('args'))})")

    return " ".join(parts) or "(empty)"


def _args(args: Any) -> str:
    """Tool arguments as JSON, which is the form they were sent in.

    `ensure_ascii=False` for the same reason the run stream sets it: Korean arguments
    escaped to \\uXXXX are unreadable in exactly the log someone opened to read them.
    """
    try:
        return json.dumps(args or {}, ensure_ascii=False, default=str)
    except (TypeError, ValueError):
        return str(args)


def _flatten(content: Any) -> str:
    """Joins the typed blocks a model may answer with into plain text."""
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        blocks = []
        for block in content:
            if isinstance(block, str):
                blocks.append(block)
            elif isinstance(block, dict) and isinstance(block.get("text"), str):
                blocks.append(block["text"])
        return "".join(blocks)
    return str(content)


def _clip(text: str) -> str:
    """Keeps one long message from burying the rest of the prompt.

    Newlines become the escape so a multi-line system prompt stays on its own numbered
    line; a prompt that wrapped freely could not be told from the next message.
    """
    flat = text.replace("\n", "\\n")
    if len(flat) <= MESSAGE_CHARS:
        return flat
    return f"{flat[:MESSAGE_CHARS]}... (+{len(flat) - MESSAGE_CHARS} chars)"


def _model_name(serialized: dict | None) -> str:
    fields = (serialized or {}).get("kwargs") or {}
    return fields.get("model") or fields.get("model_name") or "?"


def _usage(response: Any) -> dict[str, Any]:
    """Token counts, from whichever place this response carries them.

    A non-streaming reply puts them in `llm_output`; a streamed one carries
    `usage_metadata` on the message instead, and only when the model was asked to
    include it. Neither is guaranteed, so an empty dict means "not reported".
    """
    output = getattr(response, "llm_output", None) or {}
    counts = output.get("token_usage") or output.get("usage") or {}
    if counts:
        return {
            "input": counts.get("prompt_tokens"),
            "output": counts.get("completion_tokens"),
            "total": counts.get("total_tokens"),
        }

    for generations in getattr(response, "generations", None) or []:
        for generation in generations:
            message = getattr(generation, "message", None)
            metadata = getattr(message, "usage_metadata", None)
            if metadata:
                return {
                    "input": metadata.get("input_tokens"),
                    "output": metadata.get("output_tokens"),
                    "total": metadata.get("total_tokens"),
                }
    return {}
