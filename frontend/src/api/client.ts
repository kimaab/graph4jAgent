import axios, { AxiosError } from "axios";

export const API_BASE_URL =
  process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

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

export interface ToolInfo {
  name: string;
  description: string;
  parameters_schema: Record<string, unknown>;
}

/** The server answers every failure with a detail, plus per-field errors when it can. */
export interface ApiError {
  detail: string;
  errors?: Record<string, string>;
}

export class ApiRequestError extends Error {
  readonly detail: string;
  readonly fieldErrors: Record<string, string>;

  constructor(detail: string, fieldErrors: Record<string, string> = {}) {
    super(detail);
    this.name = "ApiRequestError";
    this.detail = detail;
    this.fieldErrors = fieldErrors;
  }
}

function toApiError(error: unknown): ApiRequestError {
  const axiosError = error as AxiosError<ApiError>;
  const body = axiosError.response?.data;
  if (body?.detail) {
    return new ApiRequestError(body.detail, body.errors ?? {});
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

  createAgent: (spec: AgentSpecInput) =>
    call<AgentSpec>(() => http.post("/api/agents", spec)),

  updateAgent: (id: string, spec: AgentSpecInput) =>
    call<AgentSpec>(() => http.put(`/api/agents/${id}`, spec)),

  deleteAgent: (id: string) =>
    call<void>(() => http.delete(`/api/agents/${id}`)),

  listTools: () => call<ToolInfo[]>(() => http.get("/api/tools")),

  getCode: (id: string) =>
    call<string>(() =>
      http.get(`/api/agents/${id}/code`, { responseType: "text" }),
    ),
};

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
