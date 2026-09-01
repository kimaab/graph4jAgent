package com.graph.graphtemp.graph;

import com.graph.graphtemp.agent.AgentSpec;
import com.graph.graphtemp.codegen.AgentSource;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Describes the graph an agent will run, for the flow tab.
 * <p>
 * It reads the compiled graph rather than parsing the source. Parsing would have to cope
 * with whatever a user typed — the linear template even builds its nodes in a loop, so
 * the node names are not literals in the file at all — while {@link CompiledGraph}
 * already holds exactly the nodes and edges langgraph4j will execute.
 * <p>
 * Compiling is the cost. Both {@link AgentCodeCompiler} and {@link AgentGraphs} cache on
 * the source digest, so the first view of an agent pays for javac and later ones do not,
 * and a view shares its compilation with the runner.
 */
@Component
public class GraphInspector {

    /** The generated file's tool list; see {@code templates/agent.java.template}. */
    private static final String TOOLS_METHOD = "tools";

    private final AgentSource source;
    private final AgentCodeCompiler compiler;
    private final AgentGraphs graphs;

    public GraphInspector(AgentSource source, AgentCodeCompiler compiler, AgentGraphs graphs) {
        this.source = source;
        this.compiler = compiler;
        this.graphs = graphs;
    }

    public GraphView inspect(AgentSpec spec) {
        AgentSource.Source src = source.of(spec);
        Class<?> agent = compiler.compile(src.className(), src.code(), src.digest());
        CompiledGraph<MessagesState<Message>> graph = graphs.build(spec);

        return new GraphView(src.edited(), nodes(graph), edges(graph), tools(agent));
    }

    private static List<GraphView.Node> nodes(CompiledGraph<MessagesState<Message>> graph) {
        // Node ids are the only stable handle; sorted so the same graph always lays out
        // the same way rather than following an unordered Set.
        return graph.reduce((nodes, edges) -> nodes.elements.stream()
                .map(node -> new GraphView.Node(node.id()))
                .sorted(Comparator.comparing(GraphView.Node::id))
                .toList());
    }

    private static List<GraphView.Edge> edges(CompiledGraph<MessagesState<Message>> graph) {
        return graph.reduce((nodes, edges) -> {
            List<GraphView.Edge> out = new ArrayList<>();
            edges.elements.forEach(edge -> edge.targets().forEach(target -> {
                if (target.id() != null) {
                    out.add(new GraphView.Edge(edge.sourceId(), target.id(), null));
                    return;
                }
                // A conditional edge fans out: one entry per branch the condition can
                // return, keyed by the value that selects it.
                target.value().mappings()
                        .forEach((label, to) ->
                                out.add(new GraphView.Edge(edge.sourceId(), to, label)));
            }));
            return out;
        });
    }

    /**
     * Calls the generated {@code tools()} so the list reflects the code. A user who
     * deletes a tool there changes this; the spec's own tool list does not move.
     */
    @SuppressWarnings("unchecked")
    private static List<String> tools(Class<?> agent) {
        Method method;
        try {
            method = agent.getDeclaredMethod(TOOLS_METHOD);
        } catch (NoSuchMethodException e) {
            // An edited file is allowed to drop the method; it just has no tools to show.
            return List.of();
        }
        method.setAccessible(true);

        try {
            return ((List<ToolCallback>) method.invoke(null)).stream()
                    .map(callback -> callback.getToolDefinition().name())
                    .toList();
        } catch (InvocationTargetException | IllegalAccessException | ClassCastException e) {
            throw new GraphBuildException(
                    "the agent's " + TOOLS_METHOD + "() could not be read: " + e, e);
        }
    }
}
