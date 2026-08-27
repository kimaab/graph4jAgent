package com.graph.graphtemp.graph;

import java.util.List;

/**
 * The agent's source would not compile. Carries javac's own errors so the code tab can
 * show the user which line to fix rather than a generic failure.
 */
public class CodeCompilationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient List<String> errors;

    public CodeCompilationException(String message, List<String> errors) {
        super(errors.isEmpty() ? message : message + ":\n" + String.join("\n", errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> errors() {
        return errors;
    }
}
