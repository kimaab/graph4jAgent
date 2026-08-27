package com.graph.graphtemp.agent;

import jakarta.validation.constraints.NotBlank;

/** A single node in a "linear" graph. Each step feeds its output to the next. */
public record Step(
        @NotBlank(message = "step name must not be blank") String name,
        @NotBlank(message = "step prompt must not be blank") String prompt
) {}
