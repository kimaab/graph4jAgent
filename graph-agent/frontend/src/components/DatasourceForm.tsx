"use client";

import { useState } from "react";
import type { Datasource, DatasourceInput, Driver } from "@/api/client";
import { Field, fieldClass, inputClass } from "@/components/Field";

/** The port each driver uses when the form leaves it blank. */
const DEFAULT_PORT: Record<Driver, number> = { mysql: 3306, postgresql: 5432 };

/**
 * Register or edit one database.
 *
 * `editing` is the datasource being changed, or null for a new one. The distinction
 * matters for exactly one field: on an edit the password comes back blank, because the
 * server never sends the stored one, and submitting it blank keeps what is stored.
 */
export function DatasourceForm({
  editing,
  fieldErrors,
  busy,
  onSubmit,
  onCancel,
}: {
  editing: Datasource | null;
  fieldErrors: Record<string, string>;
  busy: boolean;
  onSubmit: (spec: DatasourceInput) => void;
  onCancel: () => void;
}) {
  const [form, setForm] = useState<DatasourceInput>(() => ({
    name: editing?.name ?? "",
    description: editing?.description ?? "",
    driver: editing?.driver ?? "mysql",
    host: editing?.host ?? "",
    port: editing?.port ?? 0,
    db_name: editing?.db_name ?? "",
    db_schema: editing?.db_schema ?? "",
    username: editing?.username ?? "",
    password: "",
  }));

  function set<K extends keyof DatasourceInput>(key: K, value: DatasourceInput[K]) {
    setForm((current) => ({ ...current, [key]: value }));
  }

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        onSubmit(form);
      }}
      className="mb-6 rounded-lg border border-zinc-200 bg-white p-5 dark:border-zinc-800 dark:bg-zinc-900"
    >
      <h2 className="mb-4 text-sm font-medium">
        {editing ? `${editing.name} 편집` : "새 데이터소스"}
      </h2>

      <div className="grid gap-4 sm:grid-cols-2">
        <Field
          label="이름"
          error={fieldErrors.name}
          hint="에이전트가 이 이름으로 DB를 고릅니다. 공백은 쓸 수 없습니다."
        >
          <input
            value={form.name}
            onChange={(e) => set("name", e.target.value)}
            placeholder="aspirin"
            className={fieldClass(Boolean(fieldErrors.name))}
          />
        </Field>

        <Field label="설명" error={fieldErrors.description}>
          <input
            value={form.description}
            onChange={(e) => set("description", e.target.value)}
            placeholder="URL 수집 결과가 쌓이는 DB"
            className={fieldClass(Boolean(fieldErrors.description))}
          />
        </Field>

        <Field label="종류" error={fieldErrors.driver}>
          <select
            value={form.driver}
            onChange={(e) => set("driver", e.target.value as Driver)}
            className={inputClass}
          >
            <option value="mysql">MySQL / MariaDB</option>
            <option value="postgresql">PostgreSQL</option>
          </select>
        </Field>

        <div className="grid grid-cols-[1fr_7rem] gap-3">
          <Field label="호스트" error={fieldErrors.host}>
            <input
              value={form.host}
              onChange={(e) => set("host", e.target.value)}
              placeholder="192.168.0.10"
              className={fieldClass(Boolean(fieldErrors.host))}
            />
          </Field>
          <Field
            label="포트"
            error={fieldErrors.port}
            hint={`비우면 ${DEFAULT_PORT[form.driver]}`}
          >
            <input
              type="number"
              value={form.port === 0 ? "" : form.port}
              onChange={(e) => set("port", Number(e.target.value) || 0)}
              placeholder={String(DEFAULT_PORT[form.driver])}
              className={fieldClass(Boolean(fieldErrors.port))}
            />
          </Field>
        </div>

        <Field label="데이터베이스" error={fieldErrors.db_name}>
          <input
            value={form.db_name}
            onChange={(e) => set("db_name", e.target.value)}
            placeholder="aspirin"
            className={fieldClass(Boolean(fieldErrors.db_name))}
          />
        </Field>

        {/*
          MySQL calls a schema and a database the same thing, so asking for both there
          would be asking the same question twice — and any answer but the database name
          would be wrong.
        */}
        {form.driver === "postgresql" && (
          <Field
            label="스키마"
            error={fieldErrors.db_schema}
            hint="비우면 public"
          >
            <input
              value={form.db_schema}
              onChange={(e) => set("db_schema", e.target.value)}
              placeholder="public"
              className={fieldClass(Boolean(fieldErrors.db_schema))}
            />
          </Field>
        )}

        <Field label="사용자" error={fieldErrors.username}>
          <input
            value={form.username}
            onChange={(e) => set("username", e.target.value)}
            autoComplete="off"
            className={fieldClass(Boolean(fieldErrors.username))}
          />
        </Field>

        <Field
          label="비밀번호"
          error={fieldErrors.password}
          hint={
            editing
              ? "비워두면 저장된 비밀번호를 그대로 씁니다."
              : "스키마를 읽을 때만 서버가 사용합니다."
          }
        >
          <input
            type="password"
            value={form.password}
            onChange={(e) => set("password", e.target.value)}
            autoComplete="new-password"
            className={fieldClass(Boolean(fieldErrors.password))}
          />
        </Field>
      </div>

      <div className="mt-5 flex items-center gap-2">
        <button
          type="submit"
          disabled={busy}
          className="rounded-md bg-zinc-900 px-4 py-2 text-sm font-medium text-white
                     hover:bg-zinc-700 disabled:opacity-50
                     dark:bg-zinc-100 dark:text-zinc-900 dark:hover:bg-zinc-300"
        >
          {busy ? "저장 중..." : editing ? "저장" : "등록"}
        </button>
        <button
          type="button"
          onClick={onCancel}
          className="rounded-md border border-zinc-300 px-4 py-2 text-sm hover:bg-zinc-50 dark:border-zinc-700 dark:hover:bg-zinc-800"
        >
          취소
        </button>
        <span className="text-xs text-zinc-500">
          등록한 뒤 <b>스키마 읽기</b>를 눌러야 에이전트가 이 DB를 조회할 수 있습니다.
        </span>
      </div>
    </form>
  );
}
