package com.graph.graphtemp.codegen;

import com.graph.graphtemp.agent.AgentSpec;
import com.graph.graphtemp.agent.AgentSpecRepository;
import com.graph.graphtemp.error.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * The agent's source. Reads fall back to generating from the spec until someone edits
 * the file; from then on the stored edit is what this returns and what the runner
 * compiles, so the code tab and the run tab can never drift apart.
 */
@RestController
@RequestMapping("/api/agents")
public class CodeController {

    /** Says whether the body is the user's edit or a fresh render of the spec. */
    private static final String EDITED_HEADER = "X-Code-Edited";

    private final AgentSpecRepository repository;
    private final CodeGenerator generator;
    private final AgentSource source;

    CodeController(AgentSpecRepository repository, CodeGenerator generator, AgentSource source) {
        this.repository = repository;
        this.generator = generator;
        this.source = source;
    }

    public record CodeUpdate(@NotBlank(message = "must not be blank") String code) {}

    /** Plain text so the editor can drop it straight into a code viewer. */
    @GetMapping(value = "/{id}/code", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> code(@PathVariable UUID id) {
        AgentSpec spec = require(id);
        AgentSource.Source src = source.of(spec);
        return body(spec, src.code(), src.edited());
    }

    /** Stores an edit. From here on the spec form no longer decides what runs. */
    @PutMapping(value = "/{id}/code", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> save(@PathVariable UUID id, @Valid @RequestBody CodeUpdate update) {
        AgentSpec spec = require(id);
        repository.saveCode(id, update.code());
        return body(spec, update.code(), true);
    }

    /** Throws the edit away and renders the spec again. */
    @PostMapping(value = "/{id}/code/regenerate", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> regenerate(@PathVariable UUID id) {
        AgentSpec spec = require(id);
        repository.clearCode(id);
        return body(spec, generator.generate(spec), false);
    }

    private AgentSpec require(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> ApiException.notFound("agent not found: " + id));
    }

    private ResponseEntity<String> body(AgentSpec spec, String code, boolean edited) {
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
                .header("X-Suggested-Filename", generator.fileNameFor(spec))
                .header(EDITED_HEADER, String.valueOf(edited))
                .body(code);
    }
}
