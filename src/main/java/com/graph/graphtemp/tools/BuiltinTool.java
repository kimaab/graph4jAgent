package com.graph.graphtemp.tools;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;

/**
 * A tool the server ships with. Implementations stay pure functions of their JSON
 * argument string so the generated standalone file can mirror them.
 * <p>
 * A tool also carries what the code generator needs: the template defining its
 * standalone twin and the expression that builds it. The defaults follow the naming
 * convention — {@code CalculatorTool} ships {@code tools/calculator.java.template},
 * which defines {@code Calculator} — so adding a tool means writing a class and a
 * template, with no registration anywhere. Override these only to break the convention.
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

    /** The class the template defines: this bean's simple name without the Tool suffix. */
    public String codegenClassName() {
        String simple = getClass().getSimpleName();
        return simple.length() > 4 && simple.endsWith("Tool")
                ? simple.substring(0, simple.length() - 4)
                : simple;
    }

    /** Where the template lives, relative to {@code resources/templates/}. */
    public String codegenTemplate() {
        return "tools/" + name() + ".java.template";
    }

    /** The expression the generated file uses to build this tool. */
    public String codegenConstructor() {
        return "new " + codegenClassName() + "()";
    }

    /**
     * Extra {@code //DEPS} coordinates the generated file needs because of this tool,
     * as {@code group:artifact:version}. Most tools need nothing beyond what the agent
     * already pulls in; a tool that reaches a database or parses a format does.
     */
    public List<String> codegenDependencies() {
        return List.of();
    }
}
