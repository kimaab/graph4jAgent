package com.graph.graphtemp.agent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum GraphType {
    REACT("react"),
    LINEAR("linear");

    private final String wireName;

    GraphType(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    @JsonCreator
    public static GraphType from(String value) {
        if (value == null) {
            return null;
        }
        for (GraphType t : values()) {
            if (t.wireName.equalsIgnoreCase(value)) {
                return t;
            }
        }
        throw new IllegalArgumentException(
                "graph_type must be one of [react, linear], got: " + value);
    }
}
