"""Agent spec persistence.

`tools` and `steps` are stored as JSON text rather than jsonb: nothing queries into
them, and the Java server writes the same columns the same way.
"""

import json
from uuid import UUID, uuid4

from . import db
from .models import AgentSpec, AgentSpecInput, GraphType, Step


def find_all() -> list[AgentSpec]:
    return [_row(r) for r in db.query("SELECT * FROM agent_spec ORDER BY created_at DESC")]


def find_by_id(agent_id: UUID) -> AgentSpec | None:
    row = db.one("SELECT * FROM agent_spec WHERE id = %s", agent_id)
    return _row(row) if row else None


def insert(spec: AgentSpecInput) -> AgentSpec:
    saved = spec.with_id(uuid4())
    db.execute(
        """
        INSERT INTO agent_spec
            (id, name, description, model, system_prompt, tools, graph_type, steps, max_iterations)
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)
        """,
        saved.id,
        saved.name,
        saved.description,
        saved.model,
        saved.system_prompt,
        _json([t for t in saved.tools]),
        saved.graph_type.value,
        _json([s.model_dump() for s in saved.steps]),
        saved.max_iterations,
    )
    return saved


def update(agent_id: UUID, spec: AgentSpecInput) -> AgentSpec | None:
    """@return the saved spec, or None when no such agent exists."""
    saved = spec.with_id(agent_id)
    rows = db.execute(
        """
        UPDATE agent_spec SET
            name = %s, description = %s, model = %s, system_prompt = %s,
            tools = %s, graph_type = %s, steps = %s, max_iterations = %s,
            updated_at = now()
        WHERE id = %s
        """,
        saved.name,
        saved.description,
        saved.model,
        saved.system_prompt,
        _json([t for t in saved.tools]),
        saved.graph_type.value,
        _json([s.model_dump() for s in saved.steps]),
        saved.max_iterations,
        agent_id,
    )
    return saved if rows > 0 else None


def delete_by_id(agent_id: UUID) -> bool:
    return db.execute("DELETE FROM agent_spec WHERE id = %s", agent_id) > 0


def find_code(agent_id: UUID) -> str | None:
    """The user's edited source, absent while the agent still runs on generated code.

    Deliberately not a field of AgentSpec: the spec is the form's wire format, and a PUT
    from the editor would otherwise have to echo the whole file back to avoid wiping it.
    """
    rows = db.query("SELECT code FROM agent_spec WHERE id = %s", agent_id)
    return first_usable([r["code"] for r in rows])


def first_usable(rows: list[str | None]) -> str | None:
    """Picks the stored source out of what the query returned, or nothing.

    An agent nobody has edited has a NULL `code` — the correct state — so the query
    hands back a list holding one None. Filtering before taking the first is the whole
    point of this being its own function: the Java server took the first and then
    filtered, and every agent in the database 500'd on that None until it was fixed.
    """
    for code in rows:
        if code is not None and code.strip():
            return code
    return None


def save_code(agent_id: UUID, code: str) -> bool:
    return db.execute(
        "UPDATE agent_spec SET code = %s, code_edited_at = now(), updated_at = now() WHERE id = %s",
        code,
        agent_id,
    ) > 0


def clear_code(agent_id: UUID) -> bool:
    """Drops the edit so the next read falls back to generating from the spec."""
    return db.execute(
        "UPDATE agent_spec SET code = NULL, code_edited_at = NULL, updated_at = now() WHERE id = %s",
        agent_id,
    ) > 0


def _json(value: object) -> str:
    return json.dumps(value, ensure_ascii=False)


def _row(row: dict) -> AgentSpec:
    return AgentSpec(
        id=row["id"],
        name=row["name"],
        description=row["description"],
        model=row["model"],
        system_prompt=row["system_prompt"],
        tools=json.loads(row["tools"]),
        graph_type=GraphType(row["graph_type"]),
        steps=[Step(**s) for s in json.loads(row["steps"])],
        max_iterations=row["max_iterations"],
    )
