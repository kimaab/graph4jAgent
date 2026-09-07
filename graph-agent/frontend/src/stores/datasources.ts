"use client";

import { create } from "zustand";
import {
  ApiRequestError,
  api,
  type Datasource,
  type DatasourceInput,
  type DatasourceTable,
} from "@/api/client";

/** A blank datasource for the "new" flow. Port 0 means "use the driver's default". */
export function emptyDatasource(): DatasourceInput {
  return {
    name: "",
    description: "",
    driver: "mysql",
    host: "",
    port: 0,
    db_name: "",
    db_schema: "",
    username: "",
    password: "",
  };
}

interface DatasourcesState {
  datasources: Datasource[];
  /** Schemas already fetched, by datasource id. Only loaded when someone expands one. */
  schemas: Record<string, DatasourceTable[]>;
  loading: boolean;
  /** Which datasource is mid-sync, so the row can say so and the button can lock. */
  syncing: string | null;
  error: string | null;
  fieldErrors: Record<string, string>;

  load: () => Promise<void>;
  create: (spec: DatasourceInput) => Promise<Datasource | null>;
  update: (id: string, spec: DatasourceInput) => Promise<Datasource | null>;
  remove: (id: string) => Promise<boolean>;
  sync: (id: string) => Promise<string | null>;
  loadSchema: (id: string) => Promise<void>;
  clearErrors: () => void;
}

/**
 * Datasources and the schemas read off them.
 *
 * Mutating calls return the result on success and null on failure, leaving the reason
 * in `error`/`fieldErrors` — the same contract as the agents store, so the form code
 * looks the same on both pages.
 */
export const useDatasources = create<DatasourcesState>((set, get) => ({
  datasources: [],
  schemas: {},
  loading: false,
  syncing: null,
  error: null,
  fieldErrors: {},

  clearErrors: () => set({ error: null, fieldErrors: {} }),

  load: async () => {
    set({ loading: true, error: null });
    try {
      set({ datasources: await api.listDatasources() });
    } catch (error) {
      set({ error: messageOf(error) });
    } finally {
      set({ loading: false });
    }
  },

  create: async (spec) => {
    set({ error: null, fieldErrors: {} });
    try {
      const saved = await api.createDatasource(spec);
      set({ datasources: [...get().datasources, saved].sort(byName) });
      return saved;
    } catch (error) {
      recordError(set, error);
      return null;
    }
  },

  update: async (id, spec) => {
    set({ error: null, fieldErrors: {} });
    try {
      const saved = await api.updateDatasource(id, spec);
      set({
        datasources: get()
          .datasources.map((d) => (d.id === id ? saved : d))
          .sort(byName),
      });
      return saved;
    } catch (error) {
      recordError(set, error);
      return null;
    }
  },

  remove: async (id) => {
    set({ error: null });
    try {
      await api.deleteDatasource(id);
      const schemas = { ...get().schemas };
      delete schemas[id];
      set({ datasources: get().datasources.filter((d) => d.id !== id), schemas });
      return true;
    } catch (error) {
      set({ error: messageOf(error) });
      return false;
    }
  },

  /** @return a short summary of what was read, or null when the sync failed. */
  sync: async (id) => {
    set({ syncing: id, error: null });
    try {
      const result = await api.syncDatasource(id);
      // The row shows a table count and a sync time, and both just changed. Patching
      // them here rather than refetching the list keeps the page from flickering.
      set({
        datasources: get().datasources.map((d) =>
          d.id === id
            ? { ...d, table_count: result.table_count, synced_at: result.synced_at }
            : d,
        ),
      });
      // The stored schema was replaced, so anything already shown is stale.
      const schemas = { ...get().schemas };
      delete schemas[id];
      set({ schemas });
      return `테이블 ${result.table_count}개, 컬럼 ${result.column_count}개를 읽었습니다.`;
    } catch (error) {
      set({ error: messageOf(error) });
      return null;
    } finally {
      set({ syncing: null });
    }
  },

  loadSchema: async (id) => {
    if (get().schemas[id]) {
      return;
    }
    try {
      const tables = await api.getDatasourceSchema(id);
      set({ schemas: { ...get().schemas, [id]: tables } });
    } catch (error) {
      set({ error: messageOf(error) });
    }
  },
}));

function byName(left: Datasource, right: Datasource): number {
  return left.name.localeCompare(right.name);
}

type Setter = (partial: Partial<DatasourcesState>) => void;

function recordError(set: Setter, error: unknown) {
  if (error instanceof ApiRequestError) {
    set({ error: error.detail, fieldErrors: error.fieldErrors });
  } else {
    set({ error: messageOf(error), fieldErrors: {} });
  }
}

function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : "알 수 없는 오류";
}
