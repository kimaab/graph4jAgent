"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { emptySpec, useAgents } from "@/stores/agents";

export default function AgentListPage() {
  const router = useRouter();
  const { agents, loading, error, loadAgents, create, remove } = useAgents();
  const [creating, setCreating] = useState(false);
  const [pendingDelete, setPendingDelete] = useState<string | null>(null);

  useEffect(() => {
    void loadAgents();
  }, [loadAgents]);

  async function handleCreate() {
    setCreating(true);
    const draft = { ...emptySpec(), name: "새 에이전트" };
    const saved = await create(draft);
    setCreating(false);
    if (saved) {
      router.push(`/agents/${saved.id}`);
    }
  }

  async function handleDelete(id: string) {
    setPendingDelete(null);
    await remove(id);
  }

  return (
    <div>
      <div className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">에이전트</h1>
          <p className="mt-1 text-sm text-zinc-500">
            폼으로 정의하고, 바로 실행해보고, 실행 가능한 코드로 내보냅니다.
          </p>
        </div>
        <button
          onClick={handleCreate}
          disabled={creating}
          className="rounded-md bg-zinc-900 px-4 py-2 text-sm font-medium text-white
                     hover:bg-zinc-700 disabled:opacity-50
                     dark:bg-zinc-100 dark:text-zinc-900 dark:hover:bg-zinc-300"
        >
          {creating ? "만드는 중..." : "새 에이전트"}
        </button>
      </div>

      {error && (
        <div className="mb-4 rounded-md border border-red-300 bg-red-50 px-4 py-3 text-sm text-red-700 dark:border-red-800 dark:bg-red-950 dark:text-red-300">
          {error}
        </div>
      )}

      {loading && agents.length === 0 && (
        <p className="text-sm text-zinc-500">불러오는 중...</p>
      )}

      {!loading && agents.length === 0 && !error && (
        <div className="rounded-lg border border-dashed border-zinc-300 px-6 py-16 text-center dark:border-zinc-700">
          <p className="text-sm text-zinc-500">아직 에이전트가 없습니다.</p>
          <button
            onClick={handleCreate}
            className="mt-3 text-sm font-medium text-zinc-900 underline dark:text-zinc-100"
          >
            첫 에이전트 만들기
          </button>
        </div>
      )}

      <ul className="space-y-2">
        {agents.map((agent) => (
          <li
            key={agent.id}
            className="flex items-center gap-4 rounded-lg border border-zinc-200 bg-white px-4 py-3 dark:border-zinc-800 dark:bg-zinc-900"
          >
            <div className="min-w-0 flex-1">
              <Link
                href={`/agents/${agent.id}`}
                className="block truncate font-medium hover:underline"
              >
                {agent.name || "(이름 없음)"}
              </Link>
              <p className="truncate text-xs text-zinc-500">
                {agent.description || agent.model}
              </p>
            </div>

            <span className="rounded bg-zinc-100 px-2 py-0.5 font-mono text-xs text-zinc-600 dark:bg-zinc-800 dark:text-zinc-400">
              {agent.graph_type}
            </span>
            <span className="hidden text-xs text-zinc-500 sm:inline">
              {agent.tools.length > 0 ? agent.tools.join(", ") : "툴 없음"}
            </span>

            <Link
              href={`/agents/${agent.id}/run`}
              className="rounded-md border border-zinc-300 px-3 py-1.5 text-xs font-medium hover:bg-zinc-50 dark:border-zinc-700 dark:hover:bg-zinc-800"
            >
              실행
            </Link>

            {pendingDelete === agent.id ? (
              <span className="flex items-center gap-2 text-xs">
                <button
                  onClick={() => handleDelete(agent.id)}
                  className="rounded-md bg-red-600 px-3 py-1.5 font-medium text-white hover:bg-red-700"
                >
                  삭제
                </button>
                <button
                  onClick={() => setPendingDelete(null)}
                  className="text-zinc-500 hover:underline"
                >
                  취소
                </button>
              </span>
            ) : (
              <button
                onClick={() => setPendingDelete(agent.id)}
                className="rounded-md border border-zinc-300 px-3 py-1.5 text-xs text-zinc-600 hover:bg-zinc-50 dark:border-zinc-700 dark:text-zinc-400 dark:hover:bg-zinc-800"
              >
                삭제
              </button>
            )}
          </li>
        ))}
      </ul>
    </div>
  );
}
