package com.graph.graphtemp.graph;

import com.graph.graphtemp.agent.AgentSpec;
import com.graph.graphtemp.agent.AgentSpecRepository;
import com.graph.graphtemp.error.ApiException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Serves the flow tab the graph the agent will actually run. */
@RestController
@RequestMapping("/api/agents")
public class GraphController {

    private final AgentSpecRepository repository;
    private final GraphInspector inspector;

    GraphController(AgentSpecRepository repository, GraphInspector inspector) {
        this.repository = repository;
        this.inspector = inspector;
    }

    @GetMapping("/{id}/graph")
    public GraphView graph(@PathVariable UUID id) {
        AgentSpec spec = repository.findById(id)
                .orElseThrow(() -> ApiException.notFound("agent not found: " + id));
        return inspector.inspect(spec);
    }
}
