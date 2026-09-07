"use client";

import { useEffect, useState } from "react";
import type { Datasource, DatasourceInput } from "@/api/client";
import { DatasourceForm } from "@/components/DatasourceForm";
import { SchemaPreview } from "@/components/SchemaPreview";
import { useDatasources } from "@/stores/datasources";

export default function DatasourcePage() {
  const {
    datasources,
    schemas,
    loading,
    syncing,
    error,
    fieldErrors,
    load,
    create,
    update,
    remove,
    sync,
    loadSchema,
    clearErrors,
  } = useDatasources();

  // null = the form is closed; a datasource = editing it; "new" = registering one.
  const [form, setForm] = useState<Datasource | "new" | null>(null);
  const [saving, setSaving] = useState(false);
  const [expanded, setExpanded] = useState<string | null>(null);
  const [pendingDelete, setPendingDelete] = useState<string | null>(null);
  /** Last sync's summary, by datasource id — cleared when another sync starts. */
  const [syncNote, setSyncNote] = useState<Record<string, string>>({});

  useEffect(() => {
    void load();
  }, [load]);

  async function handleSubmit(spec: DatasourceInput) {
    setSaving(true);
    const saved =
      form === "new" || form === null
        ? await create(spec)
        : await update(form.id, spec);
    setSaving(false);
    if (saved) {
      setForm(null);
    }
  }

  async function handleSync(id: string) {
    setSyncNote((current) => ({ ...current, [id]: "" }));
    const note = await sync(id);
    if (note) {
      setSyncNote((current) => ({ ...current, [id]: note }));
      // The stored schema was just replaced, so an open panel is showing stale rows.
      if (expanded === id) {
        void loadSchema(id);
      }
    }
  }

  function handleExpand(id: string) {
    if (expanded === id) {
      setExpanded(null);
      return;
    }
    setExpanded(id);
    void loadSchema(id);
  }

  return (
    <div>
      <div className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">데이터소스</h1>
          <p className="mt-1 text-sm text-zinc-500">
            여기 등록한 DB를 <code className="font-mono text-xs">nl2sql</code> 툴이
            조회합니다. 스키마는 등록할 때 한 번 읽어 저장하므로, 대상 DB가 바뀌면 다시
            읽어야 합니다.
          </p>
        </div>
        <button
          onClick={() => {
            clearErrors();
            setForm("new");
          }}
          className="rounded-md bg-zinc-900 px-4 py-2 text-sm font-medium text-white
                     hover:bg-zinc-700 dark:bg-zinc-100 dark:text-zinc-900 dark:hover:bg-zinc-300"
        >
          새 데이터소스
        </button>
      </div>

      {error && (
        <div className="mb-4 rounded-md border border-red-300 bg-red-50 px-4 py-3 text-sm text-red-700 dark:border-red-800 dark:bg-red-950 dark:text-red-300">
          {error}
        </div>
      )}

      {form !== null && (
        <DatasourceForm
          // Remounts when the target changes, so the fields reset instead of keeping
          // the previous datasource's values.
          key={form === "new" ? "new" : form.id}
          editing={form === "new" ? null : form}
          fieldErrors={fieldErrors}
          busy={saving}
          onSubmit={handleSubmit}
          onCancel={() => {
            clearErrors();
            setForm(null);
          }}
        />
      )}

      {loading && datasources.length === 0 && (
        <p className="text-sm text-zinc-500">불러오는 중...</p>
      )}

      {!loading && datasources.length === 0 && !error && form === null && (
        <div className="rounded-lg border border-dashed border-zinc-300 px-6 py-16 text-center dark:border-zinc-700">
          <p className="text-sm text-zinc-500">아직 등록된 데이터소스가 없습니다.</p>
          <button
            onClick={() => setForm("new")}
            className="mt-3 text-sm font-medium text-zinc-900 underline dark:text-zinc-100"
          >
            첫 데이터소스 등록하기
          </button>
        </div>
      )}

      <ul className="space-y-2">
        {datasources.map((source) => (
          <li
            key={source.id}
            className="rounded-lg border border-zinc-200 bg-white dark:border-zinc-800 dark:bg-zinc-900"
          >
            <div className="flex flex-wrap items-center gap-x-4 gap-y-2 px-4 py-3">
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-2">
                  <span className="truncate font-mono font-medium">{source.name}</span>
                  <span className="rounded bg-zinc-100 px-2 py-0.5 text-xs text-zinc-600 dark:bg-zinc-800 dark:text-zinc-400">
                    {source.driver}
                  </span>
                </div>
                <p className="truncate text-xs text-zinc-500">
                  {source.username && `${source.username}@`}
                  {source.host}:{source.port}/{source.db_name}
                  {source.db_schema && ` (${source.db_schema})`}
                  {source.description && ` — ${source.description}`}
                </p>
              </div>

              <SyncState source={source} busy={syncing === source.id} />

              <button
                onClick={() => void handleSync(source.id)}
                disabled={syncing === source.id}
                className="rounded-md border border-zinc-300 px-3 py-1.5 text-xs font-medium hover:bg-zinc-50 disabled:opacity-50 dark:border-zinc-700 dark:hover:bg-zinc-800"
              >
                {syncing === source.id ? "읽는 중..." : "스키마 읽기"}
              </button>

              <button
                onClick={() => handleExpand(source.id)}
                className="rounded-md border border-zinc-300 px-3 py-1.5 text-xs hover:bg-zinc-50 dark:border-zinc-700 dark:hover:bg-zinc-800"
              >
                {expanded === source.id ? "스키마 닫기" : "스키마 보기"}
              </button>

              <button
                onClick={() => {
                  clearErrors();
                  setForm(source);
                }}
                className="rounded-md border border-zinc-300 px-3 py-1.5 text-xs hover:bg-zinc-50 dark:border-zinc-700 dark:hover:bg-zinc-800"
              >
                편집
              </button>

              {pendingDelete === source.id ? (
                <span className="flex items-center gap-2 text-xs">
                  <button
                    onClick={() => {
                      setPendingDelete(null);
                      void remove(source.id);
                    }}
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
                  onClick={() => setPendingDelete(source.id)}
                  className="rounded-md border border-zinc-300 px-3 py-1.5 text-xs text-zinc-600 hover:bg-zinc-50 dark:border-zinc-700 dark:text-zinc-400 dark:hover:bg-zinc-800"
                >
                  삭제
                </button>
              )}
            </div>

            {syncNote[source.id] && (
              <p className="border-t border-zinc-200 px-4 py-2 text-xs text-emerald-700 dark:border-zinc-800 dark:text-emerald-400">
                {syncNote[source.id]}
              </p>
            )}

            {expanded === source.id && (
              <div className="border-t border-zinc-200 dark:border-zinc-800">
                {schemas[source.id] ? (
                  <SchemaPreview tables={schemas[source.id]} />
                ) : (
                  <p className="px-4 py-3 text-xs text-zinc-500">불러오는 중...</p>
                )}
              </div>
            )}
          </li>
        ))}
      </ul>
    </div>
  );
}

/**
 * How much of this datasource the tool can actually see.
 *
 * A datasource that has never been synced is registered but useless — nl2sql lists it
 * and then finds no tables — so that state gets said plainly rather than shown as an
 * empty count.
 */
function SyncState({ source, busy }: { source: Datasource; busy: boolean }) {
  if (busy) {
    return <span className="text-xs text-zinc-500">대상 DB에 접속 중...</span>;
  }
  if (source.synced_at === null) {
    return (
      <span className="text-xs text-amber-700 dark:text-amber-500">
        스키마 없음 — 조회 불가
      </span>
    );
  }
  return (
    <span className="text-xs text-zinc-500" title={source.synced_at}>
      테이블 {source.table_count}개 · {formatWhen(source.synced_at)}
    </span>
  );
}

/** A timestamp the reader can judge staleness by, without needing the exact instant. */
function formatWhen(iso: string): string {
  const when = new Date(iso);
  if (Number.isNaN(when.getTime())) {
    return iso;
  }
  const minutes = Math.round((Date.now() - when.getTime()) / 60000);
  if (minutes < 1) {
    return "방금 읽음";
  }
  if (minutes < 60) {
    return `${minutes}분 전`;
  }
  if (minutes < 60 * 24) {
    return `${Math.round(minutes / 60)}시간 전`;
  }
  return when.toLocaleDateString();
}
