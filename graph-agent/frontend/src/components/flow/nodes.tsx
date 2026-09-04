"use client";

import type { NodeProps } from "@xyflow/react";
import { Brain, CircleDot, Flag, GitBranch, Puzzle, Wrench } from "lucide-react";
import { memo } from "react";
import { BaseNode } from "./BaseNode";

/**
 * One wrapper per node kind, following flowAI: the shell is shared and only the icon
 * and accent differ. The kinds map to what a graph actually contains here — START,
 * the model turn, the tool executor, a linear step, and END.
 */

const StartNode = memo((props: NodeProps) => (
  <BaseNode
    {...props}
    icon={<CircleDot className="h-4 w-4 text-emerald-600 dark:text-emerald-400" />}
    accent="border-emerald-500 dark:border-emerald-600"
  />
));
StartNode.displayName = "StartNode";

const AgentNode = memo((props: NodeProps) => (
  <BaseNode
    {...props}
    icon={<Brain className="h-4 w-4 text-blue-600 dark:text-blue-400" />}
    accent="border-blue-500 dark:border-blue-600"
  />
));
AgentNode.displayName = "AgentNode";

const ToolsNode = memo((props: NodeProps) => (
  <BaseNode
    {...props}
    icon={<Wrench className="h-4 w-4 text-amber-600 dark:text-amber-400" />}
    accent="border-amber-500 dark:border-amber-600"
  />
));
ToolsNode.displayName = "ToolsNode";

/**
 * One selected tool. It shares the amber accent with the tools node because it is not a
 * graph node of its own — it is one of the callbacks that node dispatches to.
 */
const ToolNode = memo((props: NodeProps) => (
  <BaseNode
    {...props}
    icon={<Puzzle className="h-4 w-4 text-amber-600 dark:text-amber-400" />}
    accent="border-amber-300 dark:border-amber-800"
  />
));
ToolNode.displayName = "ToolNode";

const StepNode = memo((props: NodeProps) => (
  <BaseNode
    {...props}
    icon={<GitBranch className="h-4 w-4 text-violet-600 dark:text-violet-400" />}
    accent="border-violet-500 dark:border-violet-600"
  />
));
StepNode.displayName = "StepNode";

const EndNode = memo((props: NodeProps) => (
  <BaseNode
    {...props}
    icon={<Flag className="h-4 w-4 text-zinc-600 dark:text-zinc-400" />}
    accent="border-zinc-400 dark:border-zinc-600"
  />
));
EndNode.displayName = "EndNode";

export const nodeTypes = {
  start: StartNode,
  agent: AgentNode,
  tools: ToolsNode,
  tool: ToolNode,
  step: StepNode,
  end: EndNode,
};

export type FlowNodeType = keyof typeof nodeTypes;
