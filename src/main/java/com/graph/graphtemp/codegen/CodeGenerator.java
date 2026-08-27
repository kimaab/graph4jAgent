package com.graph.graphtemp.codegen;

import com.graph.graphtemp.agent.AgentSpec;
import com.graph.graphtemp.agent.Step;
import com.graph.graphtemp.tools.BuiltinTool;
import com.graph.graphtemp.tools.ToolRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Renders a spec as a single self-contained Java file that runs under JBang.
 * <p>
 * The generated file pins the same langgraph4j and Spring AI versions this server
 * runs and reuses the same tool implementations, so an exported agent behaves the way
 * it did in the studio. Templates live in {@code resources/templates} and can be
 * edited without touching this class.
 */
@Component
public class CodeGenerator {

    /** Dependency versions baked into the generated //DEPS lines. */
    private static final String LANGGRAPH4J_VERSION = "1.8.24";
    private static final String SPRING_AI_VERSION = "2.0.1";
    private static final String OPENAI_SDK_VERSION = "4.49.0";
    private static final String SLF4J_VERSION = "2.0.17";

    private final ToolRegistry toolRegistry;
    private final String baseUrl;
    private final String apiKey;

    public CodeGenerator(ToolRegistry toolRegistry,
                  @Value("${spring.ai.openai.base-url}") String baseUrl,
                  @Value("${spring.ai.openai.api-key}") String apiKey) {
        this.toolRegistry = toolRegistry;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    /**
     * Fails the boot when a registered tool has no code template. Before this, a tool
     * whose template was forgotten looked fine until someone exported an agent using
     * it, and then answered 400 at that moment.
     */
    @PostConstruct
    void verifyEveryToolHasATemplate() {
        String missing = toolRegistry.all().stream()
                .filter(tool -> !new ClassPathResource("templates/" + tool.codegenTemplate()).exists())
                .map(tool -> tool.name() + " -> templates/" + tool.codegenTemplate())
                .collect(Collectors.joining(", "));

        if (!missing.isEmpty()) {
            throw new IllegalStateException("tools with no code template: " + missing);
        }
    }

    public String generate(AgentSpec spec) {
        List<BuiltinTool> tools = toolRegistry.resolveBuiltins(spec.tools().stream().distinct().toList());

        return template("agent.java.template")
                .replace("__LANGGRAPH4J__", LANGGRAPH4J_VERSION)
                .replace("__SPRING_AI__", SPRING_AI_VERSION)
                .replace("__OPENAI_SDK__", OPENAI_SDK_VERSION)
                .replace("__SLF4J__", SLF4J_VERSION)
                .replace("__AGENT_NAME__", comment(spec.name()))
                .replace("__AGENT_ID__", String.valueOf(spec.id()))
                .replace("__CLASS_NAME__", classNameFor(spec.name()))
                .replace("__BASE_URL__", escape(baseUrl))
                .replace("__API_KEY__", escape(apiKey))
                .replace("__MODEL__", escape(spec.model()))
                .replace("__SYSTEM_PROMPT__", javaString(spec.systemPrompt()))
                .replace("__MAX_ITERATIONS__", String.valueOf(spec.maxIterations()))
                .replace("__GRAPH_BUILDER__", graphBuilder(spec))
                .replace("__TOOL_CLASSES__", toolClasses(tools))
                .replace("__TOOL_INSTANCES__", toolInstances(tools));
    }

    /** The file name a client should save the generated source as. */
    public String fileNameFor(AgentSpec spec) {
        return classNameFor(spec.name()) + ".java";
    }

    private String graphBuilder(AgentSpec spec) {
        return switch (spec.graphType()) {
            case REACT -> template("graph-react.java.template");
            case LINEAR -> template("graph-linear.java.template")
                    .replace("__STEP_PROMPTS__", stepPrompts(spec.steps()));
        };
    }

    private static String stepPrompts(List<Step> steps) {
        return steps.stream()
                .map(step -> "            " + javaString(step.prompt()))
                .collect(Collectors.joining(",\n"));
    }

    private String toolClasses(List<BuiltinTool> tools) {
        return tools.stream()
                .map(tool -> template(tool.codegenTemplate()))
                .collect(Collectors.joining("\n"));
    }

    private static String toolInstances(List<BuiltinTool> tools) {
        return tools.stream()
                .map(BuiltinTool::codegenConstructor)
                .collect(Collectors.joining(", "));
    }

    private String template(String name) {
        try {
            return new ClassPathResource("templates/" + name)
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("missing code template: " + name, e);
        }
    }

    /** A legal Java identifier derived from the agent name; a non-ASCII name falls back. */
    static String classNameFor(String name) {
        StringBuilder sb = new StringBuilder();
        boolean capitalise = true;
        for (char c : name.toCharArray()) {
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9') {
                sb.append(capitalise ? Character.toUpperCase(c) : c);
                capitalise = false;
            } else {
                capitalise = true;
            }
        }
        if (sb.isEmpty() || Character.isDigit(sb.charAt(0))) {
            return "Agent";
        }
        return sb.toString();
    }

    /** Renders a value as a Java string literal, quotes included. */
    static String javaString(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    /** Escapes a value substituted inside a literal the template already opened. */
    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Keeps a free-text name from breaking out of the header comment. */
    private static String comment(String value) {
        return value.replace("*/", "* /").replace("\n", " ").replace("\r", " ");
    }
}
