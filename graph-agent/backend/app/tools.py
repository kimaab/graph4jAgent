"""The built-in tools, as the studio describes them.

What lives here is the name, description and JSON schema the model sees, plus where the
code generator finds each tool's implementation. The implementations themselves are the
templates under `templates/tools/`: every run — in the studio and standalone — executes
generated code, so a copy of the logic on the server could only drift from what runs.

Adding a tool is one entry here and one template. `verify_templates()` fails the boot if
the two disagree, rather than answering 400 the first time someone selects the tool.
"""

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from .errors import ApiException

TEMPLATES = Path(__file__).resolve().parent / "templates"


@dataclass(frozen=True)
class BuiltinTool:
    name: str
    description: str
    # What the model is told it may pass.
    parameters_schema: dict[str, Any]
    # The identifier the template defines and `tools()` collects.
    symbol: str
    # Extra PEP 723 dependencies the generated file needs because of this tool. Most
    # tools need nothing past what the agent already imports; one that reaches a
    # database does.
    dependencies: list[str] = field(default_factory=list)

    @property
    def template(self) -> str:
        """Where the implementation lives, relative to `templates/`."""
        return f"tools/{self.name}.py.template"


_STRING = "string"

REGISTRY: list[BuiltinTool] = [
    BuiltinTool(
        name="calculator",
        description="Evaluate an arithmetic expression and return the numeric result.",
        parameters_schema={
            "type": "object",
            "properties": {
                "expression": {
                    "type": _STRING,
                    "description": "Arithmetic expression, e.g. (3 + 4) * 2",
                }
            },
            "required": ["expression"],
        },
        symbol="calculator",
    ),
    BuiltinTool(
        name="http_get",
        description="Fetch the text content at an http(s) URL.",
        parameters_schema={
            "type": "object",
            "properties": {
                "url": {"type": _STRING, "description": "Absolute http(s) URL to fetch"}
            },
            "required": ["url"],
        },
        symbol="http_get",
        dependencies=["httpx"],
    ),
    BuiltinTool(
        name="web_search",
        description="Search the web and return result snippets.",
        parameters_schema={
            "type": "object",
            "properties": {"query": {"type": _STRING, "description": "Search query"}},
            "required": ["query"],
        },
        symbol="web_search",
    ),
    BuiltinTool(
        name="document_search",
        description=(
            "Search the PDF documents attached to this agent. "
            "Returns matching passages with the file name and page number."
        ),
        parameters_schema={
            "type": "object",
            "properties": {
                "query": {
                    "type": _STRING,
                    "description": "What to look for in the attached documents",
                }
            },
            "required": ["query"],
        },
        symbol="document_search",
        # The generated file talks to Postgres itself, so it carries its own driver.
        dependencies=["psycopg[binary]>=3.2"],
    ),
    BuiltinTool(
        name="semantic_sql",
        description=(
            "Turn a data question into safe SQL against the agent's data contract. "
            "Call with 'question' alone to discover the relevant tables, columns and "
            "governed metrics; then call again with 'ast' — a JSON query spec built "
            "from what the first call returned — to get the compiled SQL. Only "
            "tables, columns and metrics in the contract can be reached."
        ),
        parameters_schema={
            "type": "object",
            "properties": {
                "question": {
                    "type": _STRING,
                    "description": (
                        "Step 1. The data question, in the user's own words. "
                        "Returns the schema relevant to it."
                    ),
                },
                "ast": {
                    "type": _STRING,
                    "description": (
                        "Step 2. JSON query spec: "
                        '{"metric": "...", "filters": [{"field": "...", '
                        '"operator": "equals", "value": "..."}], "group_by": []} '
                        'or {"target_table": "...", "aggregations": '
                        '[{"field": "...", "function": "SUM", "alias": "..."}], '
                        '"filters": [], "group_by": []}'
                    ),
                },
            },
            # Neither alone is required: the two steps use one parameter each.
            "required": [],
        },
        symbol="semantic_sql",
    ),
    BuiltinTool(
        name="nl2sql",
        description=(
            "Answer a data question from one of the databases registered in the "
            "studio, returning real rows. Call with no arguments to list the "
            "available databases; with 'database' and 'tables' to see what tables a "
            "database holds; with 'database' and 'question' to discover the few "
            "tables and columns relevant to a question; then with 'database' and "
            "'ast' — a JSON query spec built from what the previous call returned — "
            "to compile the SQL, run it, and get the rows back. Only tables and "
            "columns present in the selected database's synced schema can be "
            "reached, and only SELECT can be produced."
        ),
        parameters_schema={
            "type": "object",
            "properties": {
                "database": {
                    "type": _STRING,
                    "description": (
                        "Which registered database to query. Omit every argument "
                        "to list the ones available."
                    ),
                },
                "tables": {
                    "type": _STRING,
                    "description": (
                        "List what the database contains. '*' for every table, or "
                        "part of a name to filter. Use this — not 'question' — when "
                        "asked which tables exist."
                    ),
                },
                "question": {
                    "type": _STRING,
                    "description": (
                        "Step 1. The data question, in the user's own words. "
                        "Returns the few tables closest to it, not a full listing."
                    ),
                },
                "ast": {
                    "type": _STRING,
                    "description": (
                        "Step 2. JSON query spec, run against the database. Keys: "
                        "target_table, columns, aggregations "
                        '[{"field","function":SUM|COUNT|AVG|MIN|MAX,"alias"}], '
                        'time_bucket {"field","unit":minute|hour|day|month,"alias"}, '
                        'filters [{"field","operator":equals|not_equals|'
                        "greater_than|greater_or_equal|less_than|less_or_equal|in|"
                        'not_in,"value"}], group_by, '
                        'order_by [{"field","direction":asc|desc}], limit. '
                        "Use time_bucket for any time series — raw rows will not fit."
                    ),
                },
            },
            # Every step uses a different subset, and the listing step uses none.
            "required": [],
        },
        symbol="nl2sql",
        # The generated file reads the registered schema out of the studio's own
        # Postgres and then connects to the target database itself, so it carries
        # both drivers regardless of which other tools are selected.
        dependencies=["psycopg[binary]>=3.2", "PyMySQL>=1.1"],
    ),
]

_BY_NAME = {tool.name: tool for tool in REGISTRY}


def verify_templates() -> None:
    """Fails the boot when a registered tool has no template to generate from."""
    missing = [
        f"{tool.name} -> templates/{tool.template}"
        for tool in REGISTRY
        if not (TEMPLATES / tool.template).exists()
    ]
    if missing:
        raise RuntimeError("tools with no code template: " + ", ".join(missing))


def names() -> list[str]:
    return [tool.name for tool in REGISTRY]


def find(name: str) -> BuiltinTool | None:
    return _BY_NAME.get(name)


def resolve(selected: list[str]) -> list[BuiltinTool]:
    """@raises ApiException 400 naming the unknown tool, so a bad spec is rejected at
    save time rather than at run time.
    """
    resolved = []
    for name in selected:
        tool = find(name)
        if tool is None:
            raise ApiException.bad_request(
                f"unknown tool: {name} (available: {', '.join(names())})"
            )
        resolved.append(tool)
    return resolved


def describe() -> list[dict[str, Any]]:
    return [
        {
            "name": tool.name,
            "description": tool.description,
            "parameters_schema": tool.parameters_schema,
        }
        for tool in REGISTRY
    ]
