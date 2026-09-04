"use client";

import { useEffect, useState } from "react";
import { ApiRequestError, api, type AgentCode } from "@/api/client";

/**
 * The agent's source, editable. What is saved here is what the run tab compiles, so
 * this is not a preview of the spec — past the first edit it outranks the form.
 */
interface CodeEditorProps {
  agentId: string;
  /**
   * Reports whether an edit now outranks the spec. The page shows a warning on the
   * 정의 tab from this, and saving or regenerating here is what flips it.
   */
  onEditedChange?: (edited: boolean) => void;
}

export function CodeEditor({ agentId, onEditedChange }: CodeEditorProps) {
  const [loaded, setLoaded] = useState<AgentCode | null>(null);
  const [draft, setDraft] = useState("");
  const [busy, setBusy] = useState<"loading" | "saving" | "regenerating" | null>("loading");
  const [error, setError] = useState<string | null>(null);
  const [compileErrors, setCompileErrors] = useState<string[]>([]);

  const dirty = loaded !== null && draft !== loaded.code;

  useEffect(() => {
    let cancelled = false;
    api
      .getCode(agentId)
      .then((result) => {
        if (cancelled) return;
        setLoaded(result);
        setDraft(result.code);
        onEditedChange?.(result.edited);
      })
      .catch((e: Error) => !cancelled && setError(e.message))
      .finally(() => !cancelled && setBusy(null));
    return () => {
      cancelled = true;
    };
  }, [agentId, onEditedChange]);

  async function run(
    label: "saving" | "regenerating",
    action: () => Promise<AgentCode>,
  ) {
    setBusy(label);
    setError(null);
    setCompileErrors([]);
    try {
      const result = await action();
      setLoaded(result);
      setDraft(result.code);
      onEditedChange?.(result.edited);
    } catch (e) {
      const failure = e as ApiRequestError;
      setError(failure.detail ?? failure.message);
      setCompileErrors(failure.compileErrors ?? []);
    } finally {
      setBusy(null);
    }
  }

  function download() {
    if (!loaded) return;
    const url = URL.createObjectURL(new Blob([draft], { type: "text/plain" }));
    const link = document.createElement("a");
    link.href = url;
    link.download = loaded.filename;
    link.click();
    URL.revokeObjectURL(url);
  }

  if (busy === "loading") {
    return <p className="text-sm text-zinc-500">불러오는 중...</p>;
  }

  return (
    <div>
      <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
        <p className="text-xs text-zinc-500">
          {loaded?.edited
            ? "직접 수정한 코드입니다. 실행 탭은 이 파일을 컴파일해서 돌립니다."
            : "정의로부터 생성된 코드입니다. 수정해서 저장하면 이 파일이 실행됩니다."}
        </p>
        <div className="flex shrink-0 items-center gap-2">
          <button
            onClick={download}
            className="rounded-md border border-zinc-300 px-3 py-1.5 text-xs hover:bg-zinc-50 dark:border-zinc-700 dark:hover:bg-zinc-800"
          >
            다운로드
          </button>
          <button
            onClick={() => run("regenerating", () => api.regenerateCode(agentId))}
            disabled={busy !== null}
            className="rounded-md border border-zinc-300 px-3 py-1.5 text-xs hover:bg-zinc-50 disabled:opacity-50 dark:border-zinc-700 dark:hover:bg-zinc-800"
          >
            {busy === "regenerating" ? "생성 중..." : "정의로 되돌리기"}
          </button>
          <button
            onClick={() => run("saving", () => api.saveCode(agentId, draft))}
            disabled={busy !== null || !dirty}
            className="rounded-md bg-zinc-900 px-3 py-1.5 text-xs font-medium text-white hover:bg-zinc-700 disabled:opacity-50 dark:bg-zinc-100 dark:text-zinc-900 dark:hover:bg-zinc-300"
          >
            {busy === "saving" ? "저장 중..." : dirty ? "코드 저장" : "저장됨"}
          </button>
        </div>
      </div>

      {error && (
        <div className="mb-3 rounded-md border border-red-300 bg-red-50 px-4 py-3 text-sm text-red-700 dark:border-red-800 dark:bg-red-950 dark:text-red-300">
          <p>{error}</p>
          {compileErrors.length > 0 && (
            <ul className="mt-2 space-y-1 font-mono text-xs">
              {compileErrors.map((message) => (
                <li key={message}>{message}</li>
              ))}
            </ul>
          )}
        </div>
      )}

      {dirty && !error && (
        <p className="mb-3 text-xs text-amber-700 dark:text-amber-500">
          저장하지 않은 변경이 있습니다. 저장해야 실행에 반영됩니다.
        </p>
      )}

      <textarea
        value={draft}
        onChange={(e) => setDraft(e.target.value)}
        spellCheck={false}
        // Tab is a character in Java source, not a way out of the field.
        onKeyDown={(e) => {
          if (e.key !== "Tab") return;
          e.preventDefault();
          const field = e.currentTarget;
          const { selectionStart: start, selectionEnd: end } = field;
          const next = `${draft.slice(0, start)}    ${draft.slice(end)}`;
          setDraft(next);
          requestAnimationFrame(() => {
            field.selectionStart = field.selectionEnd = start + 4;
          });
        }}
        className="h-[70vh] w-full resize-y rounded-lg border border-zinc-200 bg-white p-4 font-mono text-xs leading-relaxed focus:outline-none focus:ring-2 focus:ring-zinc-400 dark:border-zinc-800 dark:bg-zinc-900"
      />
    </div>
  );
}
