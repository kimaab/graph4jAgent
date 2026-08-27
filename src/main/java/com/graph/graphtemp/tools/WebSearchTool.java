package com.graph.graphtemp.tools;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Stub. The interface is the real one so a search backend can be dropped in without
 * touching the graph builder or the generated code; the body just says so.
 */
@Component
public class WebSearchTool extends BuiltinTool {

    private static final String SCHEMA = """
            {"type":"object",\
            "properties":{"query":{"type":"string","description":"Search query"}},\
            "required":["query"]}""";

    private final ObjectMapper mapper;

    public WebSearchTool(ObjectMapper mapper) {
        super("web_search", "Search the web and return result snippets.", SCHEMA);
        this.mapper = mapper;
    }

    @Override
    public String call(String toolInput) {
        String query;
        try {
            JsonNode node = mapper.readTree(toolInput);
            JsonNode field = node.get("query");
            query = field == null ? null : field.asString();
        } catch (RuntimeException e) {
            return "error: tool input was not valid JSON";
        }
        if (query == null || query.isBlank()) {
            return "error: 'query' is required";
        }
        return "web_search is not wired to a search backend yet. "
                + "No results available for: " + query;
    }
}
