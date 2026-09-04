"use client";

import type { ReactNode } from "react";

interface FieldProps {
  label: string;
  /** Wire field name, so server-side errors line up with the input. */
  error?: string;
  hint?: string;
  children: ReactNode;
}

export function Field({ label, error, hint, children }: FieldProps) {
  return (
    <label className="block">
      <span className="mb-1 block text-sm font-medium text-zinc-700 dark:text-zinc-300">
        {label}
      </span>
      {children}
      {hint && !error && (
        <span className="mt-1 block text-xs text-zinc-500">{hint}</span>
      )}
      {error && (
        <span className="mt-1 block text-xs text-red-600 dark:text-red-400">
          {error}
        </span>
      )}
    </label>
  );
}

export const inputClass =
  "w-full rounded-md border border-zinc-300 bg-white px-3 py-2 text-sm " +
  "text-zinc-900 outline-none focus:border-zinc-500 " +
  "dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100";

export const errorInputClass =
  "w-full rounded-md border border-red-400 bg-white px-3 py-2 text-sm " +
  "text-zinc-900 outline-none focus:border-red-500 " +
  "dark:border-red-500 dark:bg-zinc-900 dark:text-zinc-100";

export function fieldClass(hasError: boolean) {
  return hasError ? errorInputClass : inputClass;
}
