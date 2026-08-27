package com.graph.graphtemp.codegen;

import java.util.Optional;
import java.util.UUID;

/**
 * Where a user's edited source is kept. A one-method seam rather than the repository
 * itself, so the runner can be tested without a database standing behind it.
 */
public interface EditedCodeStore {

    /** Empty while the agent still runs on code generated from its spec. */
    Optional<String> findCode(UUID agentId);
}
