package com.graph.graphtemp.graph;

import com.graph.graphtemp.agent.AgentSpec;
import com.graph.graphtemp.codegen.AgentSource;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.internal.node.Node;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

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

        List<GraphView.Edge> edges = edges(graph);
        return new GraphView(src.edited(), nodes(graph, edges), edges, AgentTools.namesOf(agent));
    }

    /**
     * Every node an edge can touch. START and END are sentinels rather than registered
     * nodes, so they are absent from the graph's own node set while still being real
     * endpoints — taking only the registered nodes would leave the edges into and out of
     * the graph with nothing to attach to, and they would simply not be drawn.
     */
    private static List<GraphView.Node> nodes(CompiledGraph<MessagesState<Message>> graph,
                                              List<GraphView.Edge> edges) {
        // Sorted so the same graph always lays out the same way, rather than following
        // an unordered Set.
        // Held in a variable because reduce() infers its result type from the target,
        // and a constructor argument gives it nothing to infer from.
        List<String> declared = graph.reduce(
                (nodes, unused) -> nodes.elements.stream().map(Node::id).toList());

        Set<String> ids = new TreeSet<>(declared);
        edges.forEach(edge -> {
            ids.add(edge.source());
            ids.add(edge.target());
        });
        return ids.stream().map(GraphView.Node::new).toList();
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

}
