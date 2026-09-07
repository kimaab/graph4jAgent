import axios, { AxiosError } from "axios";

/**
 * Where the API lives.
 *
 * Under `next dev` the two run separately, so this is the backend's own origin.
 * In the static build the Python backend serves this bundle, and the value is
 * the sub-path it is mounted under ("/apps/agent-studio"), or "/" at the root.
 *
 * "/" rather than "" is the sentinel for the root because PowerShell cannot pass
 * an empty environment variable — assigning "" deletes it, and the build would
 * quietly fall back to whatever .env.local says, which is the dev server.
 */
const configured = process.env.NEXT_PUBLIC_API_BASE_URL;

export const API_BASE_URL =
  configured === undefined
    ? "http://localhost:8080"
    : configured === "/"
      ? ""
      : configured;

const http = axios.create({
  baseURL: API_BASE_URL,
  headers: { "Content-Type": "application/json; charset=utf-8" },
});

// ---------------------------------------------------------------- types

export type GraphType = "react" | "linear";

export interface Step {
  name: string;
  prompt: string;
}

/** Mirrors the server's AgentSpec. Wire format is snake_case. */
export interface AgentSpec {
  id: string;
  name: string;
  description: string;
  model: string;
  system_prompt: string;
  tools: string[];
  graph_type: GraphType;
  steps: Step[];
  max_iterations: number;
}

/** What POST/PUT accept: the server assigns the id. */
export type AgentSpecInput = Omit<AgentSpec, "id">;

/** A PDF attached to an agent; the document_search tool reads its extracted text. */
export interface AgentDocument {
  id: string;
  agent_id: string;
  filename: string;
  content_type: string;
  byte_size: number;
  page_count: number;
  uploaded_at: string;
}

export interface ToolInfo {
  name: string;
  description: string;
  parameters_schema: Record<string, unknown>;
}

/** Which database a datasource points at. Decides how its schema is read and, later,
 *  how the nl2sql tool quotes identifiers. */
export type Driver = "mysql" | "postgresql";

/**
 * A database the nl2sql tool may query.
 *
 * There is no `password` field, and that is the point: the server accepts one on write
 * and never sends it back, so no response shape here could leak it.
 */
export interface Datasource {
  id: string;
  name: string;
  description: string;
  driver: Driver;
  host: string;
  port: number;
  db_name: string;
  /** PostgreSQL only; blank means "public". MySQL has no schema apart from the database. */
  db_schema: string;
  username: string;
  /** When the schema was last read off the target database; null until a sync runs. */
  synced_at: string | null;
  table_count: number;
}

/**
 * What POST/PUT accept. `password` is write-only, and sending it blank on an update
 * keeps whatever is stored — the edit form never receives the old one to echo back.
 */
export type DatasourceInput = Omit<
  Datasource,
  "id" | "synced_at" | "table_count"
> & { password: string };

export interface DatasourceColumn {
  name: string;
  data_type: string;
  description: string;
}

export interface DatasourceTable {
  name: string;
  description: string;
  columns: DatasourceColumn[];
}

/** What a sync brought back. Zero tables means it connected and found nothing. */
export interface SyncResult {
  table_count: number;
  column_count: number;
  synced_at: string;
}

/** The server answers every failure with a detail, plus per-field errors when it can. */
export interface ApiError {
  detail: string;
  errors?: Record<string, string>;
  /** javac's own messages, when the agent's source would not compile. */
  compile_errors?: string[];
}

/**
 * The spec as the agent's source defines it, for applying an edited file back onto the
 * form. `name` and `description` are absent because the generated file does not carry
 * them — applying leaves whatever the form already has.
 */
export interface SourceSpec {
  model: string | null;
  system_prompt: string | null;
  max_iterations: number | null;
  tools: string[];
  graph_type: GraphType;
  steps: Step[];
}

/**
 * The graph the agent will actually run, read off its compiled source.
 *
 * This is not derived from the spec: once someone edits the code, the spec stops
 * describing what happens, and a picture drawn from the spec would be wrong.
 */
export interface GraphView {
  /** True when the source is a user's edit, so the 정의 tab no longer matches. */
  edited: boolean;
  nodes: { id: string }[];
  edges: GraphEdge[];
  /** What the generated `tools()` really returns. */
  tools: string[];
}

export interface GraphEdge {
  source: string;
  target: string;
  /** The value the condition returned to take this branch; null when unconditional. */
  label: string | null;
}

/** The agent's source, plus whether it is a user edit or a fresh render of the spec. */
export interface AgentCode {
  code: string;
  edited: boolean;
  filename: string;
}

export class ApiRequestError extends Error {
  readonly detail: string;
  readonly fieldErrors: Record<string, string>;
  readonly compileErrors: string[];

  constructor(
    detail: string,
    fieldErrors: Record<string, string> = {},
    compileErrors: string[] = [],
  ) {
    super(detail);
    this.name = "ApiRequestError";
    this.detail = detail;
    this.fieldErrors = fieldErrors;
    this.compileErrors = compileErrors;
  }
}

function toApiError(error: unknown): ApiRequestError {
  const axiosError = error as AxiosError<ApiError>;
  const body = axiosError.response?.data;
  if (body?.detail) {
    return new ApiRequestError(
      body.detail,
      body.errors ?? {},
      body.compile_errors ?? [],
    );
  }
  if (axiosError.request) {
    return new ApiRequestError(
      `백엔드에 연결할 수 없습니다 (${API_BASE_URL}). 서버가 실행 중인지 확인하세요.`,
    );
  }
  return new ApiRequestError(
    error instanceof Error ? error.message : "알 수 없는 오류",
  );
}

async function call<T>(run: () => Promise<{ data: T }>): Promise<T> {
  try {
    return (await run()).data;
  } catch (error) {
    throw toApiError(error);
  }
}

// ---------------------------------------------------------------- rest

export const api = {
  listAgents: () => call<AgentSpec[]>(() => http.get("/api/agents")),

  getAgent: (id: string) => call<AgentSpec>(() => http.get(`/api/agents/${id}`)),

  getGraph: (id: string) => call<GraphView>(() => http.get(`/api/agents/${id}/graph`)),

  getSourceSpec: (id: string) =>
    call<SourceSpec>(() => http.get(`/api/agents/${id}/source-spec`)),

  /** Just the edited flag — cheap enough to ask on every page load. */
  getCodeStatus: (id: string) =>
    call<{ edited: boolean }>(() => http.get(`/api/agents/${id}/code/status`)),

  createAgent: (spec: AgentSpecInput) =>
    call<AgentSpec>(() => http.post("/api/agents", spec)),

  updateAgent: (id: string, spec: AgentSpecInput) =>
    call<AgentSpec>(() => http.put(`/api/agents/${id}`, spec)),

  deleteAgent: (id: string) =>
    call<void>(() => http.delete(`/api/agents/${id}`)),

  listTools: () => call<ToolInfo[]>(() => http.get("/api/tools")),

  listDocuments: (agentId: string) =>
    call<AgentDocument[]>(() => http.get(`/api/agents/${agentId}/documents`)),

  uploadDocument: (agentId: string, file: File) => {
    const form = new FormData();
    form.append("file", file);
    // Let the browser set Content-Type: it has to add the multipart boundary.
    return call<AgentDocument>(() =>
      http.post(`/api/agents/${agentId}/documents`, form, {
        headers: { "Content-Type": undefined },
      }),
    );
  },

  deleteDocument: (agentId: string, documentId: string) =>
    call<void>(() =>
      http.delete(`/api/agents/${agentId}/documents/${documentId}`),
    ),

  listDatasources: () => call<Datasource[]>(() => http.get("/api/datasources")),

  createDatasource: (spec: DatasourceInput) =>
    call<Datasource>(() => http.post("/api/datasources", spec)),

  updateDatasource: (id: string, spec: DatasourceInput) =>
    call<Datasource>(() => http.put(`/api/datasources/${id}`, spec)),

  deleteDatasource: (id: string) =>
    call<void>(() => http.delete(`/api/datasources/${id}`)),

  /**
   * Reads the target database's catalog and replaces the stored schema with it.
   *
   * Slow by nature — it opens a connection to somebody else's database — so callers
   * should show it as work in progress rather than treat it like a save.
   */
  syncDatasource: (id: string) =>
    call<SyncResult>(() => http.post(`/api/datasources/${id}/sync`, null)),

  getDatasourceSchema: (id: string) =>
    call<DatasourceTable[]>(() => http.get(`/api/datasources/${id}/schema`)),

  getCode: (id: string) => code(() => http.get(`/api/agents/${id}/code`, TEXT)),

  saveCode: (id: string, source: string) =>
    code(() => http.put(`/api/agents/${id}/code`, { code: source }, TEXT)),

  regenerateCode: (id: string) =>
    code(() => http.post(`/api/agents/${id}/code/regenerate`, null, TEXT)),
};

const TEXT = { responseType: "text" } as const;

/**
 * The code endpoints answer in plain text and put the metadata in headers, so that a
 * plain `curl` still yields a file you can save. CORS only exposes those headers
 * because the server lists them explicitly.
 */
async function code(
  run: () => Promise<{ data: string; headers: Record<string, unknown> }>,
): Promise<AgentCode> {
  try {
    const response = await run();
    return {
      code: response.data,
      edited: String(response.headers["x-code-edited"]) === "true",
      filename: String(response.headers["x-suggested-filename"] ?? "Agent.java"),
    };
  } catch (error) {
    throw toApiError(error);
  }
}

// ---------------------------------------------------------------- sse

export type RunEvent =
  | { type: "token"; text: string }
  | { type: "tool_call"; id: string; name: string; arguments: string }
  | { type: "tool_result"; id: string; name: string; result: string }
  | { type: "done"; thread_id: string }
  | { type: "error"; detail: string };

/**
 * Streams a run.
 *
 * EventSource cannot POST and cannot set headers, and the run endpoint needs a JSON
 * body, so this reads the SSE framing off a fetch stream by hand. `signal` lets the
 * caller abort mid-run.
 */
export async function streamRun(
  agentId: string,
  body: { message: string; thread_id: string },
  onEvent: (event: RunEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
  const response = await fetch(`${API_BASE_URL}/api/agents/${agentId}/run`, {
    method: "POST",
    headers: { "Content-Type": "application/json; charset=utf-8" },
    body: JSON.stringify(body),
    signal,
  });

  if (!response.ok) {
    let detail = `실행 실패 (HTTP ${response.status})`;
    try {
      const problem = (await response.json()) as ApiError;
      if (problem.detail) {
        detail = problem.detail;
      }
    } catch {
      // Not JSON; keep the status-based message.
    }
    throw new ApiRequestError(detail);
  }
  if (!response.body) {
    throw new ApiRequestError("스트리밍 응답이 비어 있습니다.");
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  while (true) {
    const { done, value } = await reader.read();
    if (done) {
      break;
    }
    buffer += decoder.decode(value, { stream: true });

    // SSE separates events with a blank line; a chunk can split one in half.
    let split = buffer.indexOf("\n\n");
    while (split !== -1) {
      const frame = buffer.slice(0, split);
      buffer = buffer.slice(split + 2);
      const event = parseFrame(frame);
      if (event) {
        onEvent(event);
      }
      split = buffer.indexOf("\n\n");
    }
  }
}

function parseFrame(frame: string): RunEvent | null {
  let name = "";
  const dataLines: string[] = [];

  for (const rawLine of frame.split("\n")) {
    const line = rawLine.replace(/\r$/, "");
    if (line.startsWith("event:")) {
      name = line.slice(6).trim();
    } else if (line.startsWith("data:")) {
      dataLines.push(line.slice(5).trimStart());
    }
  }
  if (!name || dataLines.length === 0) {
    return null;
  }

  try {
    const payload = JSON.parse(dataLines.join("\n")) as Record<string, string>;
    return { type: name, ...payload } as RunEvent;
  } catch {
    return null;
  }
}
