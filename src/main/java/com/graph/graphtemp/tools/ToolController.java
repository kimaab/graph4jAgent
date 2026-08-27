package com.graph.graphtemp.tools;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/tools")
public class ToolController {

    private final ToolRegistry registry;

    ToolController(ToolRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    public List<ToolInfo> list() {
        return registry.describe();
    }
}
