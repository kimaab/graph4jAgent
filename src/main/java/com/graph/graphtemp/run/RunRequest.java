package com.graph.graphtemp.run;

import jakarta.validation.constraints.NotBlank;

/** Body of POST /api/agents/{id}/run. */
public record RunRequest(
        @NotBlank(message = "must not be blank") String message,
        @NotBlank(message = "must not be blank") String threadId
) {}
