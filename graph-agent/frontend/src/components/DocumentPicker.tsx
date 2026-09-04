"use client";

import { useEffect, useRef, useState } from "react";
import { api, type AgentDocument, type ApiRequestError } from "@/api/client";

/**
 * PDFs the agent can search. Uploading extracts the text once on the server; the
 * document_search tool then reads pages back out of the database.
 */
export function DocumentPicker({
  agentId,
  enabled,
}: {
  agentId: string;
  /** False while document_search is unchecked, to explain why uploads do nothing yet. */
  enabled: boolean;
}) {
  const [documents, setDocuments] = useState<AgentDocument[] | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const input = useRef<HTMLInputElement>(null);

  useEffect(() => {
    let cancelled = false;
    api
      .listDocuments(agentId)
      .then((list) => !cancelled && setDocuments(list))
      .catch((e: Error) => !cancelled && setError(e.message));
    return () => {
      cancelled = true;
    };
  }, [agentId]);

  async function upload(file: File) {
    setBusy(true);
    setError(null);
    try {
      const added = await api.uploadDocument(agentId, file);
      setDocuments((current) => [added, ...(current ?? [])]);
    } catch (e) {
      setError((e as ApiRequestError).detail ?? (e as Error).message);
    } finally {
      setBusy(false);
      if (input.current) {
        // Clear it so picking the same file again still fires onChange.
        input.current.value = "";
      }
    }
  }

  async function remove(id: string) {
    setError(null);
    try {
      await api.deleteDocument(agentId, id);
      setDocuments((current) => (current ?? []).filter((d) => d.id !== id));
    } catch (e) {
      setError((e as ApiRequestError).detail ?? (e as Error).message);
    }
  }

  return (
    <div>
      <div className="mb-2 flex items-center justify-between gap-3">
        <h2 className="text-sm font-medium">첨부 문서</h2>
        <button
          type="button"
          onClick={() => input.current?.click()}
          disabled={busy}
          className="rounded-md border border-zinc-300 px-3 py-1.5 text-xs hover:bg-zinc-50 disabled:opacity-50 dark:border-zinc-700 dark:hover:bg-zinc-800"
        >
          {busy ? "올리는 중..." : "PDF 추가"}
        </button>
      </div>

      <p className="mb-3 text-xs text-zinc-500">
        {enabled
          ? "document_search 툴이 이 문서들에서 근거를 찾습니다."
          : "document_search 툴을 켜야 에이전트가 이 문서를 읽습니다."}
      </p>

      <input
        ref={input}
        type="file"
        accept="application/pdf,.pdf"
        className="hidden"
        onChange={(e) => {
          const file = e.target.files?.[0];
          if (file) {
            void upload(file);
          }
        }}
      />

      {error && (
        <div className="mb-3 rounded-md border border-red-300 bg-red-50 px-3 py-2 text-xs text-red-700 dark:border-red-800 dark:bg-red-950 dark:text-red-300">
          {error}
        </div>
      )}

      {documents === null ? (
        <p className="text-xs text-zinc-500">불러오는 중...</p>
      ) : documents.length === 0 ? (
        <p className="text-xs text-zinc-500">아직 첨부된 문서가 없습니다.</p>
      ) : (
        <ul className="space-y-1">
          {documents.map((doc) => (
            <li
              key={doc.id}
              className="flex items-center justify-between gap-3 rounded-md border border-zinc-200 px-3 py-2 text-xs dark:border-zinc-800"
            >
              <span className="min-w-0 flex-1 truncate" title={doc.filename}>
                {doc.filename}
              </span>
              <span className="shrink-0 text-zinc-500">
                {doc.page_count}쪽 · {Math.max(1, Math.round(doc.byte_size / 1024))}KB
              </span>
              <button
                type="button"
                onClick={() => void remove(doc.id)}
                className="shrink-0 text-zinc-400 hover:text-red-600"
                aria-label={`${doc.filename} 삭제`}
              >
                삭제
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
