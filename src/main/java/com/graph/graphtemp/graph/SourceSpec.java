package com.graph.graphtemp.graph;

import com.graph.graphtemp.agent.GraphType;
import com.graph.graphtemp.agent.Step;

import java.util.List;

/**
 * The parts of a spec that can be recovered from an agent's compiled source, for
 * applying an edited file back onto the 정의 form.
 * <p>
 * Deliberately not an {@link com.graph.graphtemp.agent.AgentSpec}: {@code name} and
 * {@code description} are not recoverable. The description never reaches the generated
 * file at all, and the name only survives as a class name that
 * {@link com.graph.graphtemp.codegen.CodeGenerator#classNameFor} derived by dropping
 * every non-ASCII character — "새 에이전트" becomes "Agent", which cannot be turned back.
 * A record missing those fields says so at the type level instead of inventing them.
 * Step names are in the same position: the linear template stores only the prompts, so
 * the names come from the spec being applied onto, not from the file.
 */
public record SourceSpec(
        String model,
        String systemPrompt,
        Integer maxIterations,
        List<String> tools,
        GraphType graphType,
        List<Step> steps
) {}
