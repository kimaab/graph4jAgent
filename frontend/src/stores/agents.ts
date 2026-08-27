"use client";

import { create } from "zustand";
import {
  ApiRequestError,
  api,
  type AgentSpec,
  type AgentSpecInput,
  type ToolInfo,
} from "@/api/client";

/** A blank spec for the "new agent" flow. */
export function emptySpec(): AgentSpecInput {
  return {
    name: "",
    description: "",
    model: "google/gemma-4-31B-it",
    system_prompt: "",
    tools: [],
    graph_type: "react",
    steps: [],
    max_iterations: 10,
  };
}

interface AgentsState {
  agents: AgentSpec[];
  tools: ToolInfo[];
  loading: boolean;
  /** Top-level failure message, or null. */
  error: string | null;
  /** Server-side validation, keyed by wire field name (e.g. "system_prompt"). */
  fieldErrors: Record<string, string>;

  loadAgents: () => Promise<void>;
  loadTools: () => Promise<void>;
  create: (spec: AgentSpecInput) => Promise<AgentSpec | null>;
  update: (id: string, spec: AgentSpecInput) => Promise<AgentSpec | null>;
  remove: (id: string) => Promise<boolean>;
  clearErrors: () => void;
}

/**
 * One store for everything agent-shaped. Mutating calls return the saved spec on
 * success and null on failure, leaving the reason in `error`/`fieldErrors` so forms
 * can render it without duplicating try/catch.
 */
export const useAgents = create<AgentsState>((set, get) => ({
  agents: [],
  tools: [],
  loading: false,
  error: null,
  fieldErrors: {},

  clearErrors: () => set({ error: null, fieldErrors: {} }),

  loadAgents: async () => {
    set({ loading: true, error: null });
    try {
      set({ agents: await api.listAgents() });
    } catch (error) {
      set({ error: messageOf(error) });
    } finally {
      set({ loading: false });
    }
  },

  loadTools: async () => {
    if (get().tools.length > 0) {
      return;
    }
    try {
      set({ tools: await api.listTools() });
    } catch (error) {
      set({ error: messageOf(error) });
    }
  },

  create: async (spec) => {
    set({ error: null, fieldErrors: {} });
    try {
      const saved = await api.createAgent(spec);
      set({ agents: [saved, ...get().agents] });
      return saved;
    } catch (error) {
      recordError(set, error);
      return null;
    }
  },

  update: async (id, spec) => {
    set({ error: null, fieldErrors: {} });
    try {
      const saved = await api.updateAgent(id, spec);
      set({ agents: get().agents.map((a) => (a.id === id ? saved : a)) });
      return saved;
    } catch (error) {
      recordError(set, error);
      return null;
    }
  },

  remove: async (id) => {
    set({ error: null });
    try {
      await api.deleteAgent(id);
      set({ agents: get().agents.filter((a) => a.id !== id) });
      return true;
    } catch (error) {
      set({ error: messageOf(error) });
      return false;
    }
  },
}));

type Setter = (partial: Partial<AgentsState>) => void;

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
