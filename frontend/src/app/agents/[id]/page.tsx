"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { api, type AgentSpecInput, type GraphType } from "@/api/client";
import { Field, fieldClass } from "@/components/Field";
import { StepsEditor } from "@/components/StepsEditor";
import { ToolPicker } from "@/components/ToolPicker";
import { useAgents } from "@/stores/agents";

type Tab = "form" | "code";

export default function AgentEditorPage() {
  const { id } = useParams<{ id: string }>();
  const { tools, error, fieldErrors, loadTools, update, clearErrors } = useAgents();

  const [spec, setSpec] = useState<AgentSpecInput | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [tab, setTab] = useState<Tab>("form");
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);

  const [code, setCode] = useState<string | null>(null);
  const [codeError, setCodeError] = useState<string | null>(null);

  useEffect(() => {
    void loadTools();
  }, [loadTools]);

  useEffect(() => {
    let cancelled = false;
    api
      .getAgent(id)
      .then((loaded) => {
        if (cancelled) return;
        const { id: _ignored, ...rest } = loaded;
        setSpec(rest);
      })
      .catch((e: Error) => !cancelled && setLoadError(e.message));
    return () => {
      cancelled = true;
    };
  }, [id]);

  /** The code tab reflects what is saved on the server, so refetch after each save. */
  const loadCode = useCallback(async () => {
    setCodeError(null);
    try {
      setCode(await api.getCode(id));
    } catch (e) {
      setCodeError((e as Error).message);
    }
  }, [id]);

  useEffect(() => {
    if (tab === "code" && code === null) {
      void loadCode();
    }
  }, [tab, code, loadCode]);

  function patch(next: Partial<AgentSpecInput>) {
    setSpec((current) => (current ? { ...current, ...next } : current));
    setSaved(false);
  }

  async function handleSave() {
    if (!spec) return;
    setSaving(true);
    clearErrors();
    const result = await update(id, spec);
    setSaving(false);
    if (result) {
      setSaved(true);
      setCode(null); // regenerate on next visit to the code tab
    }
  }

  if (loadError) {
    return (
      <div className="rounded-md border border-red-300 bg-red-50 px-4 py-3 text-sm text-red-700 dark:border-red-800 dark:bg-red-950 dark:text-red-300">
        {loadError}
      </div>
    );
  }
  if (!spec) {
    return <p className="text-sm text-zinc-500">불러오는 중...</p>;
  }

  return (
    <div>
      <div className="mb-6 flex items-center justify-between gap-4">
        <div className="min-w-0">
          <Link href="/agents" className="text-xs text-zinc-500 hover:underline">
            ← 목록
          </Link>
          <h1 className="truncate text-2xl font-semibold tracking-tight">
            {spec.name || "(이름 없음)"}
          </h1>
        </div>
        <div className="flex shrink-0 items-center gap-2">
          <Link
            href={`/agents/${id}/run`}
            className="rounded-md border border-zinc-300 px-3 py-2 text-sm hover:bg-zinc-50 dark:border-zinc-700 dark:hover:bg-zinc-800"
          >
            실행해보기
          </Link>
          <button
            onClick={handleSave}
            disabled={saving}
            className="rounded-md bg-zinc-900 px-4 py-2 text-sm font-medium text-white hover:bg-zinc-700 disabled:opacity-50 dark:bg-zinc-100 dark:text-zinc-900 dark:hover:bg-zinc-300"
          >
            {saving ? "저장 중..." : saved ? "저장됨" : "저장"}
          </button>
        </div>
      </div>

      {error && (
        <div className="mb-4 rounded-md border border-red-300 bg-red-50 px-4 py-3 text-sm text-red-700 dark:border-red-800 dark:bg-red-950 dark:text-red-300">
          {error}
        </div>
      )}

      <div className="mb-4 flex gap-1 border-b border-zinc-200 dark:border-zinc-800">
        {(["form", "code"] as Tab[]).map((t) => (
          <button
            key={t}
            onClick={() => setTab(t)}
            className={`-mb-px border-b-2 px-4 py-2 text-sm ${
              tab === t
                ? "border-zinc-900 font-medium dark:border-zinc-100"
                : "border-transparent text-zinc-500"
            }`}
          >
            {t === "form" ? "정의" : "코드 보기"}
          </button>
        ))}
      </div>

      {tab === "form" ? (
        <div className="grid gap-6 lg:grid-cols-2">
          <div className="space-y-4">
            <Field label="이름" error={fieldErrors["name"]}>
              <input
                value={spec.name}
                onChange={(e) => patch({ name: e.target.value })}
                className={fieldClass(Boolean(fieldErrors["name"]))}
              />
            </Field>

            <Field label="설명" error={fieldErrors["description"]}>
              <input
                value={spec.description}
                onChange={(e) => patch({ description: e.target.value })}
                className={fieldClass(Boolean(fieldErrors["description"]))}
              />
            </Field>

            <Field
              label="모델"
              error={fieldErrors["model"]}
              hint="게이트웨이가 서빙하는 이름이어야 합니다."
            >
              <input
                value={spec.model}
                onChange={(e) => patch({ model: e.target.value })}
                className={`${fieldClass(Boolean(fieldErrors["model"]))} font-mono`}
              />
            </Field>

            <Field label="시스템 프롬프트" error={fieldErrors["system_prompt"]}>
              <textarea
                value={spec.system_prompt}
                onChange={(e) => patch({ system_prompt: e.target.value })}
                rows={6}
                className={fieldClass(Boolean(fieldErrors["system_prompt"]))}
              />
            </Field>

            <div className="grid grid-cols-2 gap-4">
              <Field label="그래프 종류" error={fieldErrors["graph_type"]}>
                <select
                  value={spec.graph_type}
                  onChange={(e) =>
                    patch({ graph_type: e.target.value as GraphType })
                  }
                  className={fieldClass(Boolean(fieldErrors["graph_type"]))}
                >
                  <option value="react">react — 툴을 쓰며 스스로 반복</option>
                  <option value="linear">linear — 정해진 순서대로</option>
                </select>
              </Field>

              <Field
                label="최대 반복"
                error={fieldErrors["max_iterations"]}
                hint="react 루프의 상한"
              >
                <input
                  type="number"
                  min={1}
                  max={50}
                  value={spec.max_iterations}
                  onChange={(e) =>
                    patch({ max_iterations: Number(e.target.value) })
                  }
                  className={fieldClass(Boolean(fieldErrors["max_iterations"]))}
                />
              </Field>
            </div>
          </div>

          <div className="space-y-4">
            <div>
              <h2 className="mb-2 text-sm font-medium text-zinc-700 dark:text-zinc-300">
                툴
              </h2>
              <ToolPicker
                tools={tools}
                selected={spec.tools}
                onChange={(next) => patch({ tools: next })}
              />
              {fieldErrors["tools"] && (
                <p className="mt-1 text-xs text-red-600">{fieldErrors["tools"]}</p>
              )}
            </div>

            {spec.graph_type === "linear" && (
              <div>
                <h2 className="mb-2 text-sm font-medium text-zinc-700 dark:text-zinc-300">
                  단계
                </h2>
                <StepsEditor
                  steps={spec.steps}
                  fieldErrors={fieldErrors}
                  onChange={(next) => patch({ steps: next })}
                />
              </div>
            )}
          </div>
        </div>
      ) : (
        <div>
          <div className="mb-3 flex items-center justify-between">
            <p className="text-xs text-zinc-500">
              저장된 정의로 생성된 코드입니다. <code>jbang Agent.java &quot;질문&quot;</code> 으로 실행합니다.
            </p>
            <button
              onClick={loadCode}
              className="rounded-md border border-zinc-300 px-3 py-1.5 text-xs hover:bg-zinc-50 dark:border-zinc-700 dark:hover:bg-zinc-800"
            >
              새로 생성
            </button>
          </div>
          {codeError ? (
            <div className="rounded-md border border-red-300 bg-red-50 px-4 py-3 text-sm text-red-700 dark:border-red-800 dark:bg-red-950 dark:text-red-300">
              {codeError}
            </div>
          ) : code === null ? (
            <p className="text-sm text-zinc-500">생성 중...</p>
          ) : (
            <pre className="max-h-[70vh] overflow-auto rounded-lg border border-zinc-200 bg-white p-4 text-xs leading-relaxed dark:border-zinc-800 dark:bg-zinc-900">
              <code>{code}</code>
            </pre>
          )}
        </div>
      )}
    </div>
  );
}
