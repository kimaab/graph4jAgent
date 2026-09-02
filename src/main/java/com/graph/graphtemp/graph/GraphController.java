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
    private final SourceSpecReader specReader;

    GraphController(AgentSpecRepository repository, GraphInspector inspector,
                    SourceSpecReader specReader) {
        this.repository = repository;
        this.inspector = inspector;
        this.specReader = specReader;
    }

    @GetMapping("/{id}/graph")
    public GraphView graph(@PathVariable UUID id) {
        return inspector.inspect(require(id));
    }

    /**
     * The spec as the agent's source actually defines it, for applying an edited file
     * back onto the form. Answers from the source whether or not it has been edited;
     * for an unedited agent it simply matches the spec.
     */
    @GetMapping("/{id}/source-spec")
    public SourceSpec sourceSpec(@PathVariable UUID id) {
        return specReader.read(require(id));
    }

    private AgentSpec require(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> ApiException.notFound("agent not found: " + id));
    }
}
