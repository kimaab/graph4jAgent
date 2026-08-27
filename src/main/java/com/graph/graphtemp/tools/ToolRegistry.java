package com.graph.graphtemp.tools;

import com.graph.graphtemp.error.ApiException;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Look-up by name for the built-in tools. Spring supplies every {@link BuiltinTool} bean. */
@Component
public class ToolRegistry {

    private final Map<String, BuiltinTool> byName = new LinkedHashMap<>();
    private final ObjectMapper mapper;

    public ToolRegistry(List<BuiltinTool> tools, ObjectMapper mapper) {
        this.mapper = mapper;
        for (BuiltinTool tool : tools) {
            BuiltinTool clash = byName.put(tool.name(), tool);
            if (clash != null) {
                throw new IllegalStateException("duplicate tool name: " + tool.name());
            }
        }
    }

    public Optional<BuiltinTool> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    public List<String> names() {
        return List.copyOf(byName.keySet());
    }

    /**
     * @throws ApiException 400 naming the unknown tool, so a bad spec is rejected at
     *         save time rather than at run time.
     */
    public List<ToolCallback> resolve(List<String> names) {
        return names.stream()
                .map(name -> (ToolCallback) find(name).orElseThrow(() -> ApiException.badRequest(
                        "unknown tool: " + name + " (available: " + String.join(", ", names()) + ")")))
                .toList();
    }

    public List<ToolInfo> describe() {
        return byName.values().stream()
                .map(tool -> new ToolInfo(
                        tool.name(),
                        tool.description(),
                        mapper.readTree(tool.inputSchema())))
                .toList();
    }
}
