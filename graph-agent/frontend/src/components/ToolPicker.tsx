"use client";

import type { ToolInfo } from "@/api/client";

interface ToolPickerProps {
  tools: ToolInfo[];
  selected: string[];
  onChange: (tools: string[]) => void;
}

export function ToolPicker({ tools, selected, onChange }: ToolPickerProps) {
  function toggle(name: string) {
    onChange(
      selected.includes(name)
        ? selected.filter((t) => t !== name)
        : [...selected, name],
    );
  }

  if (tools.length === 0) {
    return <p className="text-sm text-zinc-500">툴 목록을 불러오는 중...</p>;
  }

  return (
    <div className="space-y-2">
      {tools.map((tool) => (
        <label
          key={tool.name}
          className="flex cursor-pointer items-start gap-3 rounded-md border border-zinc-200 px-3 py-2 hover:bg-zinc-50 dark:border-zinc-800 dark:hover:bg-zinc-800"
        >
          <input
            type="checkbox"
            checked={selected.includes(tool.name)}
            onChange={() => toggle(tool.name)}
            className="mt-0.5"
          />
          <span className="min-w-0">
            <span className="block font-mono text-sm">{tool.name}</span>
            <span className="block text-xs text-zinc-500">
              {tool.description}
            </span>
          </span>
        </label>
      ))}
    </div>
  );
}
