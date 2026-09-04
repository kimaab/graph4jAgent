"use client";

import { Handle, Position, type NodeProps } from "@xyflow/react";
import { Fragment, type ReactNode } from "react";

export interface BaseNodeData extends Record<string, unknown> {
  label: string;
  /** Second line — a step prompt, a tool list. Truncated to two lines. */
  detail?: string;
  /** Sides an edge actually attaches to; the rest draw no dot. */
  sides?: Side[];
}

export type Side = "top" | "bottom" | "left" | "right";

const POSITION: Record<Side, Position> = {
  top: Position.Top,
  bottom: Position.Bottom,
  left: Position.Left,
  right: Position.Right,
};

/** Handle ids an edge refers to. Every side carries both directions — see below. */
export const sourceHandle = (side: Side) => `${side}-s`;
export const targetHandle = (side: Side) => `${side}-t`;

interface BaseNodeProps extends NodeProps {
  icon: ReactNode;
  /** Border + icon tint. The body stays zinc so the accent reads as a category. */
  accent: string;
}

/**
 * The shared shell every node type wraps, ported from flowAI's BaseNode. Two changes:
 * handles are display-only because this canvas is a viewer, and the body follows the
 * app's zinc palette so the node is legible in dark mode.
 */
export function BaseNode({ data, selected, icon, accent }: BaseNodeProps) {
  const { label, detail, sides = [] } = data as BaseNodeData;

  return (
    <div
      className={`w-52 rounded-md border-2 bg-white px-3 py-2 shadow-sm transition-shadow dark:bg-zinc-900 ${accent} ${
        selected ? "ring-2 ring-zinc-400 dark:ring-zinc-500" : ""
      }`}
    >
      {/*
       * Both directions on every used side. The graph comes from compiled code, so an
       * edge may leave or enter any side — a back edge climbs out the left, a fan-out
       * exits right — and a side fixed to one direction would leave it unattachable.
       * Only the target dot is painted; two visible dots per side would just be noise.
       */}
      {sides.map((side) => (
        <Fragment key={side}>
          <Handle
            type="target"
            position={POSITION[side]}
            id={targetHandle(side)}
            className="!h-2 !w-2 !border-0 !bg-zinc-400 dark:!bg-zinc-500"
          />
          <Handle
            type="source"
            position={POSITION[side]}
            id={sourceHandle(side)}
            className="!h-2 !w-2 !border-0 !bg-transparent"
          />
        </Fragment>
      ))}

      <div className="flex items-center gap-2">
        <span className="flex h-5 w-5 shrink-0 items-center justify-center">{icon}</span>
        <span className="truncate text-sm font-medium text-zinc-900 dark:text-zinc-100">
          {label}
        </span>
      </div>
      {detail && (
        <p className="mt-1 line-clamp-2 text-xs leading-snug text-zinc-500 dark:text-zinc-400">
          {detail}
        </p>
      )}

    </div>
  );
}
