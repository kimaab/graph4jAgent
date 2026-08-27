package com.graph.graphtemp.agent;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * The single source of truth for an agent. Wire format is snake_case
 * (see {@link com.graph.graphtemp.config.JacksonConfig}).
 * <p>
 * {@code id} is assigned by the server: it is ignored on POST and taken from
 * the path on PUT.
 */
public record AgentSpec(
        UUID id,

        @NotBlank(message = "must not be blank")
        String name,

        String description,

        @NotBlank(message = "must not be blank")
        String model,

        String systemPrompt,

        // Built-in tool names; validated against the registry once it exists.
        List<String> tools,

        @NotNull(message = "must be one of [react, linear]")
        GraphType graphType,

        // Only used when graphType == LINEAR.
        @Valid
        List<Step> steps,

        @Min(value = 1, message = "must be at least 1")
        @Max(value = 50, message = "must be at most 50")
        Integer maxIterations
) {
    /** Normalises nulls so the rest of the code never has to null-check collections. */
    public AgentSpec {
        description = description == null ? "" : description;
        systemPrompt = systemPrompt == null ? "" : systemPrompt;
        tools = tools == null ? List.of() : List.copyOf(tools);
        steps = steps == null ? List.of() : List.copyOf(steps);
        maxIterations = maxIterations == null ? 10 : maxIterations;
    }

    @JsonIgnore
    @AssertTrue(message = "a linear graph needs at least one step")
    public boolean isStepsConsistent() {
        return graphType != GraphType.LINEAR || !steps.isEmpty();
    }

    public AgentSpec withId(UUID newId) {
        return new AgentSpec(newId, name, description, model, systemPrompt,
                tools, graphType, steps, maxIterations);
    }
}
