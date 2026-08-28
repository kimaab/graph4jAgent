package com.graph.graphtemp.tools;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Searches the PDFs uploaded to this agent and returns matching passages with their
 * page numbers.
 * <p>
 * The working implementation is {@code templates/tools/document_search.java.template},
 * not this class. Every run — in the studio and standalone — executes the generated
 * file, and only there is the agent's id a known constant; a shared bean like this one
 * has no way to tell which agent is asking. What this class contributes is the name,
 * description and schema the model sees, plus the JDBC driver the generated file needs.
 */
@Component
public class DocumentSearchTool extends BuiltinTool {

    private static final String SCHEMA = """
            {"type":"object",\
            "properties":{"query":{"type":"string",\
            "description":"What to look for in the attached documents"}},\
            "required":["query"]}""";

    public DocumentSearchTool() {
        super("document_search",
                "Search the PDF documents attached to this agent. "
                        + "Returns matching passages with the file name and page number.",
                SCHEMA);
    }

    @Override
    public List<String> codegenDependencies() {
        return List.of("org.postgresql:postgresql:42.7.13");
    }

    @Override
    public String call(String toolInput) {
        return "document_search runs from the agent's generated code, which knows which "
                + "agent it belongs to. Reaching this message means it was invoked on the "
                + "server bean instead.";
    }
}
