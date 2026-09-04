"use client";

import type { Step } from "@/api/client";
import { fieldClass } from "./Field";

interface StepsEditorProps {
  steps: Step[];
  /** Server-side errors keyed like "steps[0].name". */
  fieldErrors: Record<string, string>;
  onChange: (steps: Step[]) => void;
}

export function StepsEditor({ steps, fieldErrors, onChange }: StepsEditorProps) {
  function update(index: number, patch: Partial<Step>) {
    onChange(steps.map((s, i) => (i === index ? { ...s, ...patch } : s)));
  }

  function add() {
    onChange([...steps, { name: `단계 ${steps.length + 1}`, prompt: "" }]);
  }

  function remove(index: number) {
    onChange(steps.filter((_, i) => i !== index));
  }

  /** Swaps with the neighbour; order is the whole point of a linear graph. */
  function move(index: number, delta: number) {
    const target = index + delta;
    if (target < 0 || target >= steps.length) {
      return;
    }
    const next = [...steps];
    [next[index], next[target]] = [next[target], next[index]];
    onChange(next);
  }

  const stepsError = fieldErrors["steps"];

  return (
    <div className="space-y-3">
      {stepsError && (
        <p className="text-xs text-red-600 dark:text-red-400">{stepsError}</p>
      )}

      {steps.map((step, index) => (
        <div
          key={index}
          className="rounded-md border border-zinc-200 p-3 dark:border-zinc-800"
        >
          <div className="mb-2 flex items-center gap-2">
            <span className="rounded bg-zinc-100 px-2 py-0.5 font-mono text-xs text-zinc-600 dark:bg-zinc-800 dark:text-zinc-400">
              {index + 1}
            </span>
            <input
              value={step.name}
              onChange={(e) => update(index, { name: e.target.value })}
              placeholder="단계 이름"
              className={`${fieldClass(
                Boolean(fieldErrors[`steps[${index}].name`]),
              )} flex-1`}
            />
            <button
              type="button"
              onClick={() => move(index, -1)}
              disabled={index === 0}
              aria-label="위로"
              className="rounded border border-zinc-300 px-2 py-1 text-xs disabled:opacity-30 dark:border-zinc-700"
            >
              ↑
            </button>
            <button
              type="button"
              onClick={() => move(index, 1)}
              disabled={index === steps.length - 1}
              aria-label="아래로"
              className="rounded border border-zinc-300 px-2 py-1 text-xs disabled:opacity-30 dark:border-zinc-700"
            >
              ↓
            </button>
            <button
              type="button"
              onClick={() => remove(index)}
              className="rounded border border-zinc-300 px-2 py-1 text-xs text-red-600 dark:border-zinc-700"
            >
              삭제
            </button>
          </div>

          <textarea
            value={step.prompt}
            onChange={(e) => update(index, { prompt: e.target.value })}
            rows={3}
            placeholder="이 단계에서 모델에게 시킬 일"
            className={fieldClass(
              Boolean(fieldErrors[`steps[${index}].prompt`]),
            )}
          />
          {(fieldErrors[`steps[${index}].name`] ||
            fieldErrors[`steps[${index}].prompt`]) && (
            <p className="mt-1 text-xs text-red-600 dark:text-red-400">
              {fieldErrors[`steps[${index}].name`] ??
                fieldErrors[`steps[${index}].prompt`]}
            </p>
          )}
        </div>
      ))}

      <button
        type="button"
        onClick={add}
        className="rounded-md border border-dashed border-zinc-300 px-3 py-2 text-sm text-zinc-600 hover:bg-zinc-50 dark:border-zinc-700 dark:text-zinc-400 dark:hover:bg-zinc-800"
      >
        + 단계 추가
      </button>
    </div>
  );
}
