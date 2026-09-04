"use client";

import {
  Background,
  BackgroundVariant,
  Controls,
  MiniMap,
  ReactFlow,
  ReactFlowProvider,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import { useEffect, useMemo, useState } from "react";
import { api, type GraphView as Graph } from "@/api/client";
import { edgeTypes } from "./flow/ConditionalEdge";
import { graphToFlow } from "./flow/graphToFlow";
import { nodeTypes } from "./flow/nodes";

interface FlowViewProps {
  agentId: string;
}

/** Mirrors each node type's accent so the minimap is a legible thumbnail of the graph. */
const NODE_COLORS: Record<string, string> = {
  start: "#10b981",
  agent: "#3b82f6",
  tools: "#f59e0b",
  tool: "#fcd34d",
  step: "#8b5cf6",
  end: "#a1a1aa",
};

const nodeColor = (node: { type?: string }) => NODE_COLORS[node.type ?? ""] ?? "#a1a1aa";

/**
 * The graph the agent will actually run.
 *
 * It comes from the server, which reads it off the compiled source rather than off the
 * spec. That is the whole point: once someone edits the code the 정의 tab no longer
 * decides what happens, and a diagram drawn from the spec would show tools that were
 * deleted and miss nodes that were added.
 */
function FlowViewInner({ agentId }: FlowViewProps) {
  const [graph, setGraph] = useState<Graph | null>(null);
  const [error, setError] = useState<string | null>(null);

  // The page remounts this on save (key={codeEpoch}), so one fetch per mount is enough
  // and a stale graph never lingers on screen.
  useEffect(() => {
    let cancelled = false;
    api
      .getGraph(agentId)
      .then((loaded) => !cancelled && setGraph(loaded))
      .catch((e: Error) => !cancelled && setError(e.message));
    return () => {
      cancelled = true;
    };
  }, [agentId]);

  const flow = useMemo(() => (graph ? graphToFlow(graph) : null), [graph]);

  if (error) {
    return (
      <div className="rounded-md border border-red-300 bg-red-50 px-4 py-3 text-sm text-red-700 dark:border-red-800 dark:bg-red-950 dark:text-red-300">
        <p className="font-medium">그래프를 그릴 수 없습니다.</p>
        {/* The source has to compile to be read, so this is usually javac talking. */}
        <p className="mt-1 whitespace-pre-wrap font-mono text-xs">{error}</p>
      </div>
    );
  }
  if (!flow || !graph) {
    return <p className="text-sm text-zinc-500">컴파일하는 중...</p>;
  }

  return (
    <div>
      <p className="mb-3 text-xs text-zinc-500">
        {graph.edited
          ? "직접 수정한 코드에서 읽은 그래프입니다. 정의 탭과 다를 수 있습니다."
          : "정의로부터 생성된 코드에서 읽은 그래프입니다."}
      </p>

      <div className="h-[600px] rounded-md border border-zinc-200 dark:border-zinc-800">
        <ReactFlow
          nodes={flow.nodes}
          edges={flow.edges}
          nodeTypes={nodeTypes}
          edgeTypes={edgeTypes}
          fitView
          fitViewOptions={{ padding: 0.2 }}
          minZoom={0.2}
          // globals.css themes on prefers-color-scheme, so React Flow's own controls,
          // background and edges have to follow the same signal rather than a stored choice.
          colorMode="system"
          // A viewer: pan and zoom stay, everything that would mutate the graph goes.
          nodesDraggable={false}
          nodesConnectable={false}
          elementsSelectable={false}
          edgesFocusable={false}
        >
          <Background variant={BackgroundVariant.Dots} gap={12} size={1} />
          <Controls showInteractive={false} />
          {/* Without an explicit nodeColor the minimap paints nodes in their
              (transparent) computed background and reads as an empty box. */}
          <MiniMap
            pannable
            zoomable
            nodeColor={nodeColor}
            nodeStrokeWidth={3}
            className="!bg-zinc-100 dark:!bg-zinc-800"
          />
        </ReactFlow>
      </div>
    </div>
  );
}

export function FlowView(props: FlowViewProps) {
  return (
    <ReactFlowProvider>
      <FlowViewInner {...props} />
    </ReactFlowProvider>
  );
}
