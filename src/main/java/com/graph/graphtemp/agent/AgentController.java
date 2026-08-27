package com.graph.graphtemp.agent;

import com.graph.graphtemp.error.ApiException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * CRUD over the agent spec. No service layer yet: this is straight persistence,
 * and the graph/codegen behaviour that would justify one arrives in a later step.
 */
@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private final AgentSpecRepository repository;

    AgentController(AgentSpecRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<AgentSpec> list() {
        return repository.findAll();
    }

    @GetMapping("/{id}")
    public AgentSpec get(@PathVariable UUID id) {
        return repository.findById(id).orElseThrow(() -> notFound(id));
    }

    /** The client-supplied id is ignored; the server always assigns one. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AgentSpec create(@Valid @RequestBody AgentSpec spec) {
        AgentSpec saved = spec.withId(UUID.randomUUID());
        repository.insert(saved);
        return saved;
    }

    @PutMapping("/{id}")
    public AgentSpec update(@PathVariable UUID id, @Valid @RequestBody AgentSpec spec) {
        AgentSpec saved = spec.withId(id);
        if (!repository.update(saved)) {
            throw notFound(id);
        }
        return saved;
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        if (!repository.deleteById(id)) {
            throw notFound(id);
        }
    }

    private static ApiException notFound(UUID id) {
        return ApiException.notFound("agent not found: " + id);
    }
}
