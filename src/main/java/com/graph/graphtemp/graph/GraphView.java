package com.graph.graphtemp.graph;

import java.util.List;

/**
 * The graph an agent actually runs, read off the compiled source rather than off the
 * spec. Once someone edits the code the spec stops describing what happens, so a view
 * derived from the spec would quietly show the wrong picture.
 *
 * @param edited whether this came from a user's edited source; false means the code was
 *               generated from the spec and the two still agree
 * @param tools  the callbacks {@code tools()} really returns, which is what a user
 *               deleting a tool from the code changes
 */
public record GraphView(
        boolean edited,
        List<Node> nodes,
        List<Edge> edges,
        List<String> tools
) {
    /** A graph node. {@code __START__} and {@code __END__} are included as themselves. */
    public record Node(String id) {}

    /**
     * @param label the value the condition returned to pick this branch; null for an
     *              unconditional edge
     */
    public record Edge(String source, String target, String label) {}
}
