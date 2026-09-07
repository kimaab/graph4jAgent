"""The wire format. Field names are already snake_case, so nothing is aliased.

Validation messages are kept identical to the Java server's, because the editor shows
them verbatim under the offending input.
"""

from enum import Enum
from typing import Any
from uuid import UUID

from pydantic import BaseModel, Field, field_validator, model_validator


class GraphType(str, Enum):
    REACT = "react"
    LINEAR = "linear"


LINEAR_NEEDS_A_STEP = "a linear graph needs at least one step"

# A rule about the spec as a whole has no field of its own: pydantic reports a
# `model_validator` failure with an empty location, and an error with no location cannot
# be shown against an input. These say which input each such rule belongs to, so the
# editor can highlight it. Keyed by message because that is all pydantic carries out.
WHOLE_BODY_FIELDS = {LINEAR_NEEDS_A_STEP: "steps"}


class Step(BaseModel):
    """A single node in a "linear" graph. Each step feeds its output to the next."""

    name: str
    prompt: str

    @field_validator("name")
    @classmethod
    def _name_present(cls, value: str) -> str:
        if not value or not value.strip():
            raise ValueError("step name must not be blank")
        return value

    @field_validator("prompt")
    @classmethod
    def _prompt_present(cls, value: str) -> str:
        if not value or not value.strip():
            raise ValueError("step prompt must not be blank")
        return value


class AgentSpecInput(BaseModel):
    """What POST and PUT accept. The server assigns the id, so it is not a field here."""

    name: str
    description: str = ""
    model: str
    system_prompt: str = ""
    tools: list[str] = Field(default_factory=list)
    graph_type: GraphType
    steps: list[Step] = Field(default_factory=list)
    max_iterations: int = 10

    @field_validator("name", "model")
    @classmethod
    def _not_blank(cls, value: str) -> str:
        if not value or not value.strip():
            raise ValueError("must not be blank")
        return value

    @field_validator("max_iterations")
    @classmethod
    def _in_range(cls, value: int) -> int:
        if value < 1:
            raise ValueError("must be at least 1")
        if value > 50:
            raise ValueError("must be at most 50")
        return value

    @model_validator(mode="after")
    def _steps_consistent(self) -> "AgentSpecInput":
        if self.graph_type is GraphType.LINEAR and not self.steps:
            raise ValueError(LINEAR_NEEDS_A_STEP)
        return self

    def with_id(self, new_id: UUID) -> "AgentSpec":
        return AgentSpec(id=new_id, **self.model_dump())


class AgentSpec(AgentSpecInput):
    """A stored agent. `id` is the server's, never the client's."""

    id: UUID


class AgentDocument(BaseModel):
    """One uploaded file's metadata; its text lives a page at a time in the database."""

    id: UUID
    agent_id: UUID
    filename: str
    content_type: str
    byte_size: int
    page_count: int
    uploaded_at: str


class ToolInfo(BaseModel):
    """What GET /api/tools returns; the editor renders one checkbox per entry."""

    name: str
    description: str
    parameters_schema: dict[str, Any]


class GraphNode(BaseModel):
    """`__START__` and `__END__` are included as themselves."""

    id: str


class GraphEdge(BaseModel):
    source: str
    target: str
    # The value the condition returned to take this branch; null when unconditional.
    label: str | None = None


class GraphView(BaseModel):
    """The graph the agent will actually run, read off its own source.

    Not derived from the spec: once someone edits the code the spec stops describing
    what happens, and a picture drawn from the spec would be wrong.
    """

    edited: bool
    nodes: list[GraphNode]
    edges: list[GraphEdge]
    # What the source's own `tools()` really returns.
    tools: list[str]


class SourceSpec(BaseModel):
    """The parts of a spec recoverable from an agent's source, for applying an edit back.

    `name` and `description` are absent because the generated file does not carry them:
    the description never reaches it, and the name survives only as a module docstring.
    Step names are in the same position — the linear template stores only prompts — so
    they come from the spec being applied onto, not from the file.
    """

    model: str | None
    system_prompt: str | None
    max_iterations: int | None
    tools: list[str]
    graph_type: GraphType
    steps: list[Step]


class RunRequest(BaseModel):
    message: str
    thread_id: str

    @field_validator("message", "thread_id")
    @classmethod
    def _not_blank(cls, value: str) -> str:
        if not value or not value.strip():
            raise ValueError("must not be blank")
        return value


class CodeUpdate(BaseModel):
    code: str

    @field_validator("code")
    @classmethod
    def _not_blank(cls, value: str) -> str:
        if not value or not value.strip():
            raise ValueError("must not be blank")
        return value


class CodeStatus(BaseModel):
    """Whether an edit outranks the spec for this agent."""

    edited: bool


class Driver(str, Enum):
    """대상 DB의 종류. 인용 문자와 information_schema 질의가 여기서 갈립니다."""

    MYSQL = "mysql"
    POSTGRESQL = "postgresql"


DEFAULT_PORTS = {Driver.MYSQL: 3306, Driver.POSTGRESQL: 5432}


class DatasourceInput(BaseModel):
    """등록 화면이 보내는 것. 비밀번호는 들어오기만 하고 나가지 않습니다."""

    name: str
    description: str = ""
    driver: Driver
    host: str
    port: int = 0
    db_name: str
    # PostgreSQL에서만 의미가 있습니다. 비우면 'public'을 읽습니다.
    db_schema: str = ""
    username: str = ""
    password: str = ""

    @field_validator("name")
    @classmethod
    def _name_usable(cls, value: str) -> str:
        # 모델이 인자로 적어 보낼 이름입니다. 공백이 섞이면 그때그때 다르게
        # 적히고, 어느 쪽도 등록된 이름과 맞지 않습니다.
        cleaned = (value or "").strip()
        if not cleaned:
            raise ValueError("must not be blank")
        if any(c.isspace() for c in cleaned):
            raise ValueError("must not contain spaces: the model passes it as a name")
        return cleaned

    @field_validator("host", "db_name")
    @classmethod
    def _not_blank(cls, value: str) -> str:
        if not value or not value.strip():
            raise ValueError("must not be blank")
        return value.strip()

    @model_validator(mode="after")
    def _port_defaulted(self) -> "DatasourceInput":
        # 0은 "안 적었다"는 뜻입니다. 폼에서 비워둔 것을 거절하는 대신 드라이버의
        # 기본 포트를 씁니다 — 3306과 5432를 외우게 할 이유가 없습니다.
        if self.port == 0:
            self.port = DEFAULT_PORTS[self.driver]
        if not 1 <= self.port <= 65535:
            raise ValueError("port must be between 1 and 65535")
        return self


class Datasource(BaseModel):
    """저장된 데이터소스. `password`가 없는 것이 이 클래스의 요점입니다."""

    id: UUID
    name: str
    description: str
    driver: Driver
    host: str
    port: int
    db_name: str
    db_schema: str
    username: str
    # 마지막으로 스키마를 읽어온 시각. 한 번도 동기화하지 않았으면 null이고,
    # 그 상태의 데이터소스는 nl2sql에게 빈 스키마로 보입니다.
    synced_at: str | None
    table_count: int


class DatasourceColumn(BaseModel):
    name: str
    data_type: str
    description: str


class DatasourceTable(BaseModel):
    name: str
    description: str
    columns: list[DatasourceColumn]


class SyncResult(BaseModel):
    """동기화가 무엇을 가져왔는지. 0이면 접속은 됐지만 읽을 게 없었다는 뜻입니다."""

    table_count: int
    column_count: int
    synced_at: str
