"use client";

import { useMemo, useState } from "react";
import type { DatasourceTable } from "@/api/client";
import { inputClass } from "@/components/Field";

/**
 * What the last sync stored, as the nl2sql tool will see it.
 *
 * Comments are given as much room as names because they are what the tool actually
 * searches: its pruner ranks tables by the text of their comments, so a schema that
 * arrived without any is one the model can only find by identifier — which, for a
 * question asked in Korean, means it will not find it at all. Showing them here is how
 * that gets noticed before an agent quietly answers badly.
 */
export function SchemaPreview({ tables }: { tables: DatasourceTable[] }) {
  const [filter, setFilter] = useState("");
  const [open, setOpen] = useState<string | null>(null);

  const matching = useMemo(() => {
    const needle = filter.trim().toLowerCase();
    if (!needle) {
      return tables;
    }
    return tables.filter(
      (table) =>
        table.name.toLowerCase().includes(needle) ||
        table.description.toLowerCase().includes(needle) ||
        table.columns.some(
          (column) =>
            column.name.toLowerCase().includes(needle) ||
            column.description.toLowerCase().includes(needle),
        ),
    );
  }, [tables, filter]);

  if (tables.length === 0) {
    return (
      <p className="px-4 py-3 text-xs text-zinc-500">
        저장된 스키마가 없습니다. <b>스키마 읽기</b>를 눌러 대상 DB에서 가져오세요.
      </p>
    );
  }

  const commented = tables.filter((t) => t.description).length;

  return (
    <div className="px-4 py-3">
      <div className="mb-3 flex flex-wrap items-center gap-3">
        <input
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
          placeholder="테이블·컬럼·설명 검색"
          className={`${inputClass} max-w-xs !py-1.5 !text-xs`}
        />
        <span className="text-xs text-zinc-500">
          테이블 {tables.length}개 · 설명이 있는 테이블 {commented}개
        </span>
        {commented === 0 && (
          // Not an error, but the single thing most likely to make the tool useless.
          <span className="text-xs text-amber-700 dark:text-amber-500">
            설명(코멘트)이 하나도 없습니다 — 한글 질문으로는 테이블이 잘 안 잡힙니다.
          </span>
        )}
      </div>

      {matching.length === 0 ? (
        <p className="text-xs text-zinc-500">검색 결과가 없습니다.</p>
      ) : (
        <ul className="space-y-1">
          {matching.map((table) => (
            <li
              key={table.name}
              className="rounded-md border border-zinc-200 dark:border-zinc-800"
            >
              <button
                type="button"
                onClick={() => setOpen(open === table.name ? null : table.name)}
                className="flex w-full items-center gap-3 px-3 py-2 text-left text-xs hover:bg-zinc-50 dark:hover:bg-zinc-800"
                aria-expanded={open === table.name}
              >
                <span className="shrink-0 text-zinc-400">
                  {open === table.name ? "▾" : "▸"}
                </span>
                <span className="font-mono font-medium">{table.name}</span>
                <span className="min-w-0 flex-1 truncate text-zinc-500">
                  {table.description || "(설명 없음)"}
                </span>
                <span className="shrink-0 text-zinc-400">
                  컬럼 {table.columns.length}
                </span>
              </button>

              {open === table.name && (
                <div className="overflow-x-auto border-t border-zinc-200 dark:border-zinc-800">
                  <table className="w-full text-xs">
                    <tbody>
                      {table.columns.map((column) => (
                        <tr
                          key={column.name}
                          className="border-b border-zinc-100 last:border-0 dark:border-zinc-800/60"
                        >
                          <td className="whitespace-nowrap px-3 py-1.5 font-mono">
                            {column.name}
                          </td>
                          <td className="whitespace-nowrap px-3 py-1.5 text-zinc-500">
                            {column.data_type}
                          </td>
                          <td className="px-3 py-1.5 text-zinc-600 dark:text-zinc-400">
                            {column.description}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
