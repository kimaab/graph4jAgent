"use client";

import { useState } from "react";

interface ToolBlockProps {
  name: string;
  /** Raw JSON arguments as the model produced them. */
  args?: string;
  /** Tool output, once it arrives. */
  result?: string;
}

/**
 * One tool invocation, collapsed by default. Arguments and result share a block so a
 * call and its answer read as one thing rather than two loose events.
 */
export function ToolBlock({ name, args, result }: ToolBlockProps) {
  const [open, setOpen] = useState(false);
  const pending = result === undefined;

  return (
    <div className="my-2 rounded-md border border-amber-300 bg-amber-50 text-xs dark:border-amber-800 dark:bg-amber-950">
      <button
        onClick={() => setOpen(!open)}
        className="flex w-full items-center gap-2 px-3 py-2 text-left"
      >
        <span className="text-amber-700 dark:text-amber-400">{open ? "▾" : "▸"}</span>
        <span className="font-mono font-medium text-amber-900 dark:text-amber-200">
          {name}
        </span>
        {pending ? (
          <span className="text-amber-700 dark:text-amber-400">실행 중...</span>
        ) : (
          <span className="truncate text-amber-800 dark:text-amber-300">
            → {result}
          </span>
        )}
      </button>

      {open && (
        <div className="space-y-2 border-t border-amber-200 px-3 py-2 dark:border-amber-800">
          <div>
            <span className="block text-amber-700 dark:text-amber-400">인자</span>
            <pre className="overflow-x-auto whitespace-pre-wrap break-all font-mono text-zinc-800 dark:text-zinc-200">
              {args ?? "(없음)"}
            </pre>
          </div>
          <div>
            <span className="block text-amber-700 dark:text-amber-400">결과</span>
            <pre className="overflow-x-auto whitespace-pre-wrap break-all font-mono text-zinc-800 dark:text-zinc-200">
              {result ?? "(대기 중)"}
            </pre>
          </div>
        </div>
      )}
    </div>
  );
}
