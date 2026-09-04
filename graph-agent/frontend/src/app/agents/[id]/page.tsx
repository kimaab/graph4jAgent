"use client";

import Link from "next/link";
import { useAgentId } from "@/hooks/useAgentId";
import { useEffect, useState } from "react";
import { api, type AgentSpecInput, type GraphType } from "@/api/client";
import { CodeEditor } from "@/components/CodeEditor";
import { DocumentPicker } from "@/components/DocumentPicker";
import { Field, fieldClass } from "@/components/Field";
import { FlowView } from "@/components/FlowView";
import { StepsEditor } from "@/components/StepsEditor";
import { ToolPicker } from "@/components/ToolPicker";
import { useAgents } from "@/stores/agents";

type Tab = "form" | "code" | "flow";

export default function AgentEditorPage() {
  const id = useAgentId();
  const { tools, error, fieldErrors, loadTools, update, clearErrors } = useAgents();

  const [spec, setSpec] = useState<AgentSpecInput | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [tab, setTab] = useState<Tab>("form");
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);

  /** Remounts the editor after a spec save, so unedited code reflects the new spec. */
  const [codeEpoch, setCodeEpoch] = useState(0);

  /**
   * Whether an edit to the source now outranks this form. Null until known — the warning
   * should not flash on a page that turns out to have no edit.
   */
  const [codeEdited, setCodeEdited] = useState<boolean | null>(null);

  const [applying, setApplying] = useState(false);
  const [applyError, setApplyError] = useState<string | null>(null);
  /** Set after a successful apply, to point out that the form is not saved yet. */
  const [applied, setApplied] = useState(false);

  useEffect(() => {
    void loadTools();
  }, [loadTools]);

  useEffect(() => {
    // Null while the router has not settled on a route yet; see useAgentId.
    if (!id) return;
    let cancelled = false;
    api
      .getCodeStatus(id)
      .then((status) => !cancelled && setCodeEdited(status.edited))
      // A failure here only costs the warning; the editor still reports the truth.
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [id]);

  useEffect(() => {
    if (!id) return;
    let cancelled = false;
    api
      .getAgent(id)
      .then((loaded) => {
        if (cancelled) return;
        const { id: _ignored, ...rest } = loaded;
        setSpec(rest);
        // Cleared on success, so a failed earlier attempt cannot leave the editor
        // showing an error over data that did in fact arrive.
        setLoadError(null);
      })
      .catch((e: Error) => !cancelled && setLoadError(e.message));
    return () => {
      cancelled = true;
    };
  }, [id]);

  function patch(next: Partial<AgentSpecInput>) {
    setSpec((current) => (current ? { ...current, ...next } : current));
    setSaved(false);
  }

  async function handleSave() {
    // Unreachable from the rendered UI, which only exists once both are known;
    // stated so the compiler can see it too.
    if (!spec || !id) return;
    setSaving(true);
    clearErrors();
    const result = await update(id, spec);
    setSaving(false);
    if (result) {
      setSaved(true);
      setApplied(false);
      setCodeEpoch((n) => n + 1);
    }
  }

  /**
   * Pulls the definition back out of the edited source and into this form.
   *
   * It only fills the form; saving is still the user's call, so an apply that reads
   * something unexpected can be undone by reloading rather than by editing it back.
   * Name and description are left alone because the generated file does not carry them.
   */
  async function applyFromSource() {
    if (!id) return;
    setApplying(true);
    setApplyError(null);
    try {
      const source = await api.getSourceSpec(id);
      patch({
        ...(source.model !== null && { model: source.model }),
        ...(source.system_prompt !== null && { system_prompt: source.system_prompt }),
        ...(source.max_iterations !== null && { max_iterations: source.max_iterations }),
        tools: source.tools,
        graph_type: source.graph_type,
        steps: source.steps,
      });
      setApplied(true);
    } catch (e) {
      setApplyError((e as Error).message);
    } finally {
      setApplying(false);
    }
  }

  // Before the error branch, and before anything that hands `id` to a child: the router
  // has not settled on a route yet, so there is nothing to show and nothing to report.
  // Rendering the error here instead is what made a normal click into the editor end at
  // "'agent_id' is not a valid value: _id".
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
        {(["form", "code", "flow"] as Tab[]).map((t) => (
          <button
            key={t}
            onClick={() => setTab(t)}
            className={`-mb-px border-b-2 px-4 py-2 text-sm ${
              tab === t
                ? "border-zinc-900 font-medium dark:border-zinc-100"
                : "border-transparent text-zinc-500"
            }`}
          >
            {t === "form" ? "정의" : t === "code" ? "코드 보기" : "플로우"}
          </button>
        ))}
      </div>

      {tab === "form" && codeEdited && (
        <div className="mb-4 rounded-md border border-amber-300 bg-amber-50 px-4 py-3 text-sm dark:border-amber-800 dark:bg-amber-950">
          <p className="font-medium text-amber-900 dark:text-amber-200">
            이 에이전트는 직접 수정한 코드로 실행됩니다.
          </p>
          <p className="mt-1 text-amber-800 dark:text-amber-300">
            아래 정의는 실행되는 것과 다를 수 있습니다.{" "}
            <button onClick={() => setTab("code")} className="underline underline-offset-2">
              코드 보기
            </button>{" "}
            탭에서 확인할 수 있습니다.
          </p>

          <button
            onClick={applyFromSource}
            disabled={applying}
            className="mt-3 rounded-md border border-amber-400 px-3 py-1.5 text-xs font-medium text-amber-900 hover:bg-amber-100 disabled:opacity-50 dark:border-amber-700 dark:text-amber-200 dark:hover:bg-amber-900"
          >
            {applying ? "읽는 중..." : "소스를 정의에 적용"}
          </button>

          {applied && (
            <p className="mt-2 text-xs text-amber-800 dark:text-amber-300">
              소스에서 읽어 아래 폼에 채웠습니다. 저장해야 정의에 반영됩니다. 이름과 설명은
              소스에 없어 그대로 두었습니다.
            </p>
          )}
          {applyError && (
            <p className="mt-2 text-xs text-red-700 dark:text-red-400">{applyError}</p>
          )}
        </div>
      )}

      {tab === "form" && (
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

            <div className="border-t border-zinc-200 pt-4 dark:border-zinc-800">
              <DocumentPicker
                agentId={id}
                enabled={spec.tools.includes("document_search")}
              />
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
      )}
      {tab === "code" && (
        <CodeEditor key={codeEpoch} agentId={id} onEditedChange={setCodeEdited} />
      )}
      {tab === "flow" && <FlowView key={codeEpoch} agentId={id} />}
    </div>
  );
}
