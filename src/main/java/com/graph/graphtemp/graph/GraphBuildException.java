package com.graph.graphtemp.graph;

/** A spec that cannot be turned into a graph; surfaced to the client as 400. */
public class GraphBuildException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public GraphBuildException(String message, Throwable cause) {
        super(message, cause);
    }
}
