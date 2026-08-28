package com.graph.graphtemp.document;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One uploaded file's metadata. The text itself lives a page at a time in
 * {@code agent_document_page}, because a search answers with a page number.
 */
public record AgentDocument(
        UUID id,
        UUID agentId,
        String filename,
        String contentType,
        long byteSize,
        int pageCount,
        OffsetDateTime uploadedAt
) {}
