package com.graph.graphtemp.document;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Uploaded documents and their page text. */
@Repository
public class AgentDocumentRepository {

    private final JdbcTemplate jdbc;

    AgentDocumentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Metadata and pages land together or not at all. */
    @Transactional
    public AgentDocument insert(UUID agentId, String filename, String contentType,
                                long byteSize, List<String> pages) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agent_document
                    (id, agent_id, filename, content_type, byte_size, page_count)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, agentId, filename, contentType, byteSize, pages.size());

        // Counted, not indexOf: two identical pages (two blank ones, say) would both
        // resolve to the first one's number and collide on the unique key.
        List<Object[]> rows = new ArrayList<>(pages.size());
        for (int i = 0; i < pages.size(); i++) {
            // An empty page still gets a row so page numbers keep matching the file.
            rows.add(new Object[]{id, i + 1, pages.get(i)});
        }
        jdbc.batchUpdate("""
                INSERT INTO agent_document_page (document_id, page_number, content)
                VALUES (?, ?, ?)
                """, rows);

        return findById(id).orElseThrow(
                () -> new IllegalStateException("document vanished right after insert: " + id));
    }

    public List<AgentDocument> findByAgent(UUID agentId) {
        return jdbc.query("""
                SELECT * FROM agent_document WHERE agent_id = ? ORDER BY uploaded_at DESC
                """, mapper(), agentId);
    }

    public Optional<AgentDocument> findById(UUID id) {
        return jdbc.query("SELECT * FROM agent_document WHERE id = ?", mapper(), id)
                .stream().findFirst();
    }

    /** Pages go with it: the foreign key cascades. */
    public boolean delete(UUID agentId, UUID documentId) {
        return jdbc.update("DELETE FROM agent_document WHERE id = ? AND agent_id = ?",
                documentId, agentId) > 0;
    }

    private static RowMapper<AgentDocument> mapper() {
        return (ResultSet rs, int rowNum) -> new AgentDocument(
                rs.getObject("id", UUID.class),
                rs.getObject("agent_id", UUID.class),
                rs.getString("filename"),
                rs.getString("content_type"),
                rs.getLong("byte_size"),
                rs.getInt("page_count"),
                rs.getObject("uploaded_at", OffsetDateTime.class));
    }
}
