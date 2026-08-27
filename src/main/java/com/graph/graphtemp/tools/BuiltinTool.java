package com.graph.graphtemp.tools;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * A tool the server ships with. Implementations stay pure functions of their JSON
 * argument string so the generated standalone Python file can mirror them.
 */
public abstract class BuiltinTool implements ToolCallback {

    private final ToolDefinition definition;

    protected BuiltinTool(String name, String description, String inputSchema) {
        this.definition = ToolDefinition.builder()
                .name(name)
                .description(description)
                .inputSchema(inputSchema)
                .build();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    public String name() {
        return definition.name();
    }

    public String description() {
        return definition.description();
    }

    public String inputSchema() {
        return definition.inputSchema();
    }
}
