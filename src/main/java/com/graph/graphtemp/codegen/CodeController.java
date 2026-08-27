package com.graph.graphtemp.codegen;

import com.graph.graphtemp.agent.AgentSpec;
import com.graph.graphtemp.agent.AgentSpecRepository;
import com.graph.graphtemp.error.ApiException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RestController
@RequestMapping("/api/agents")
public class CodeController {

    private final AgentSpecRepository repository;
    private final CodeGenerator generator;

    CodeController(AgentSpecRepository repository, CodeGenerator generator) {
        this.repository = repository;
        this.generator = generator;
    }

    /** Plain text so the editor can drop it straight into a code viewer. */
    @GetMapping(value = "/{id}/code", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> code(@PathVariable UUID id) {
        AgentSpec spec = repository.findById(id)
                .orElseThrow(() -> ApiException.notFound("agent not found: " + id));

        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
                .header("X-Suggested-Filename", generator.fileNameFor(spec))
                .body(generator.generate(spec));
    }
}
