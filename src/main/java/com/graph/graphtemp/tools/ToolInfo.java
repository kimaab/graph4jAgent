package com.graph.graphtemp.tools;

import tools.jackson.databind.JsonNode;

/** What GET /api/tools returns; the editor renders one checkbox per entry. */
public record ToolInfo(String name, String description, JsonNode parametersSchema) {}
