package com.graph.graphtemp.agent;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Plain JdbcTemplate persistence. {@code tools} and {@code steps} are stored as
 * JSON text: an MVP does not query into them, so a jsonb column would only add
 * driver coupling.
 */
@Repository
public class AgentSpecRepository {

    private static final TypeReference<List<String>> TOOLS_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<Step>> STEPS_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    AgentSpecRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public List<AgentSpec> findAll() {
        return jdbc.query("SELECT * FROM agent_spec ORDER BY created_at DESC", rowMapper());
    }

    public Optional<AgentSpec> findById(UUID id) {
        return jdbc.query("SELECT * FROM agent_spec WHERE id = ?", rowMapper(), id)
                .stream().findFirst();
    }

    public void insert(AgentSpec spec) {
        jdbc.update("""
                INSERT INTO agent_spec
                    (id, name, description, model, system_prompt, tools, graph_type, steps, max_iterations)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                spec.id(), spec.name(), spec.description(), spec.model(), spec.systemPrompt(),
                writeJson(spec.tools()), spec.graphType().wireName(), writeJson(spec.steps()),
                spec.maxIterations());
    }

    /** @return true when a row was actually updated. */
    public boolean update(AgentSpec spec) {
        int rows = jdbc.update("""
                UPDATE agent_spec SET
                    name = ?, description = ?, model = ?, system_prompt = ?,
                    tools = ?, graph_type = ?, steps = ?, max_iterations = ?,
                    updated_at = now()
                WHERE id = ?
                """,
                spec.name(), spec.description(), spec.model(), spec.systemPrompt(),
                writeJson(spec.tools()), spec.graphType().wireName(), writeJson(spec.steps()),
                spec.maxIterations(), spec.id());
        return rows > 0;
    }

    public boolean deleteById(UUID id) {
        return jdbc.update("DELETE FROM agent_spec WHERE id = ?", id) > 0;
    }

    private RowMapper<AgentSpec> rowMapper() {
        return (ResultSet rs, int rowNum) -> new AgentSpec(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("model"),
                rs.getString("system_prompt"),
                readJson(rs.getString("tools"), TOOLS_TYPE),
                GraphType.from(rs.getString("graph_type")),
                readJson(rs.getString("steps"), STEPS_TYPE),
                rs.getInt("max_iterations"));
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialise agent spec field", e);
        }
    }

    private <T> T readJson(String json, TypeReference<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("corrupt agent spec row: " + json, e);
        }
    }
}
