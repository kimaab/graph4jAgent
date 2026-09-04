"use client";

import Link from "next/link";
import { useAgentId } from "@/hooks/useAgentId";
import { useEffect, useRef, useState } from "react";
import { api, streamRun, type AgentSpec, type RunEvent } from "@/api/client";
import { ToolBlock } from "@/components/ToolBlock";

interface ToolCall {
  id: string;
  name: string;
  args: string;
  result?: string;
}

interface Turn {
  question: string;
  /** Assistant text, appended token by token. */
  answer: string;
  toolCalls: ToolCall[];
  error?: string;
  done: boolean;
}

function newThreadId() {
  return `web-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
}

export default function AgentRunPage() {
  const id = useAgentId();

  const [agent, setAgent] = useState<AgentSpec | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [threadId, setThreadId] = useState("");
  const [turns, setTurns] = useState<Turn[]>([]);
  const [input, setInput] = useState("");
  const [running, setRunning] = useState(false);

  const abortRef = useRef<AbortController | null>(null);
  const bottomRef = useRef<HTMLDivElement | null>(null);

  // A thread is created per visit; "새 대화" starts another one.
  useEffect(() => setThreadId(newThreadId()), []);

  useEffect(() => {
    // Null while the router has not settled on a route yet; see useAgentId.
    if (!id) return;
    let cancelled = false;
    api
      .getAgent(id)
      .then((loaded) => {
        if (cancelled) return;
        setAgent(loaded);
        setLoadError(null);
      })
      .catch((e: Error) => !cancelled && setLoadError(e.message));
    return () => {
      cancelled = true;
    };
  }, [id]);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [turns]);

  useEffect(() => () => abortRef.current?.abort(), []);

  /** Applies one SSE event to the turn currently streaming (always the last one). */
  function apply(event: RunEvent) {
    setTurns((current) => {
      const next = [...current];
      const turn = { ...next[next.length - 1] };

      switch (event.type) {
        case "token":
          turn.answer += event.text;
          break;
        case "tool_call":
          turn.toolCalls = [
            ...turn.toolCalls,
            { id: event.id, name: event.name, args: event.arguments },
          ];
          break;
        case "tool_result":
          turn.toolCalls = turn.toolCalls.map((call) =>
            call.id === event.id ? { ...call, result: event.result } : call,
          );
          break;
        case "done":
          turn.done = true;
          break;
        case "error":
          turn.error = event.detail;
          turn.done = true;
          break;
      }

      next[next.length - 1] = turn;
      return next;
    });
  }

  async function send() {
    const question = input.trim();
    // `id` is never null once the composer is on screen; checked before any state moves
    // so a guard cannot strand the UI in "실행 중...".
    if (!question || running || !id) {
      return;
    }
    setInput("");
    setRunning(true);
    setTurns((current) => [
      ...current,
      { question, answer: "", toolCalls: [], done: false },
    ]);

    const controller = new AbortController();
    abortRef.current = controller;

    try {
      await streamRun(id, { message: question, thread_id: threadId }, apply, controller.signal);
    } catch (e) {
      if (!controller.signal.aborted) {
        apply({ type: "error", detail: (e as Error).message });
      }
    } finally {
      setRunning(false);
      abortRef.current = null;
    }
  }

  function reset() {
    abortRef.current?.abort();
    setTurns([]);
    setThreadId(newThreadId());
    setRunning(false);
  }

  // Before the error branch: the router has not settled on a route yet, so there is
  // nothing to report. See useAgentId.
  if (!id) {
    return <p className="text-sm text-zinc-500">불러오는 중...</p>;
  }
  if (loadError) {
    return (
      <div className="rounded-md border border-red-300 bg-red-50 px-4 py-3 text-sm text-red-700 dark:border-red-800 dark:bg-red-950 dark:text-red-300">
        {loadError}
      </div>
    );
  }

  return (
    <div className="flex h-[calc(100vh-10rem)] flex-col">
      <div className="mb-4 flex items-start justify-between gap-4">
        <div className="min-w-0">
          <Link href={`/agents/${id}`} className="text-xs text-zinc-500 hover:underline">
            ← 에디터
          </Link>
          <h1 className="truncate text-2xl font-semibold tracking-tight">
            {agent?.name ?? "불러오는 중..."}
          </h1>
          <p className="mt-0.5 font-mono text-xs text-zinc-500">
            thread {threadId || "..."}
          </p>
        </div>
        <button
          onClick={reset}
          className="shrink-0 rounded-md border border-zinc-300 px-3 py-2 text-sm hover:bg-zinc-50 dark:border-zinc-700 dark:hover:bg-zinc-800"
        >
          새 대화
        </button>
      </div>

      <div className="flex-1 space-y-6 overflow-y-auto rounded-lg border border-zinc-200 bg-white p-4 dark:border-zinc-800 dark:bg-zinc-900">
        {turns.length === 0 && (
          <p className="py-16 text-center text-sm text-zinc-500">
            메시지를 보내 에이전트를 시험해보세요.
          </p>
        )}

        {turns.map((turn, index) => (
          <div key={index} className="space-y-2">
            <div className="flex justify-end">
              <p className="max-w-[80%] whitespace-pre-wrap rounded-lg bg-zinc-900 px-3 py-2 text-sm text-white dark:bg-zinc-100 dark:text-zinc-900">
                {turn.question}
              </p>
            </div>

            {turn.toolCalls.map((call) => (
              <ToolBlock
                key={call.id}
                name={call.name}
                args={call.args}
                result={call.result}
              />
            ))}

            {turn.answer && (
              <p className="max-w-[80%] whitespace-pre-wrap text-sm leading-relaxed">
                {turn.answer}
                {!turn.done && <span className="animate-pulse">▍</span>}
              </p>
            )}

            {!turn.answer && !turn.done && turn.toolCalls.length === 0 && (
              <p className="text-sm text-zinc-500">생각하는 중...</p>
            )}

            {turn.error && (
              <p className="rounded-md border border-red-300 bg-red-50 px-3 py-2 text-xs text-red-700 dark:border-red-800 dark:bg-red-950 dark:text-red-300">
                {turn.error}
              </p>
            )}
          </div>
        ))}
        <div ref={bottomRef} />
      </div>

      <form
        onSubmit={(e) => {
          e.preventDefault();
          void send();
        }}
        className="mt-4 flex gap-2"
      >
        <input
          value={input}
          onChange={(e) => setInput(e.target.value)}
          placeholder="메시지를 입력하세요"
          disabled={running}
          className="flex-1 rounded-md border border-zinc-300 bg-white px-3 py-2 text-sm outline-none focus:border-zinc-500 disabled:opacity-60 dark:border-zinc-700 dark:bg-zinc-900"
        />
        <button
          type="submit"
          disabled={running || !input.trim()}
          className="rounded-md bg-zinc-900 px-4 py-2 text-sm font-medium text-white hover:bg-zinc-700 disabled:opacity-50 dark:bg-zinc-100 dark:text-zinc-900 dark:hover:bg-zinc-300"
        >
          {running ? "실행 중..." : "보내기"}
        </button>
      </form>
    </div>
  );
}
