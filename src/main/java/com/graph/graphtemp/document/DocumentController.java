package com.graph.graphtemp.document;

import com.graph.graphtemp.agent.AgentSpecRepository;
import com.graph.graphtemp.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;

/**
 * Reference material an agent can search. Uploading extracts the text once; the
 * {@code document_search} tool reads it back out of the database.
 */
@RestController
@RequestMapping("/api/agents/{agentId}/documents")
public class DocumentController {

    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);

    private final AgentSpecRepository agents;
    private final AgentDocumentRepository documents;
    private final PdfTextExtractor extractor;

    DocumentController(AgentSpecRepository agents, AgentDocumentRepository documents,
                       PdfTextExtractor extractor) {
        this.agents = agents;
        this.documents = documents;
        this.extractor = extractor;
    }

    @GetMapping
    public List<AgentDocument> list(@PathVariable UUID agentId) {
        requireAgent(agentId);
        return documents.findByAgent(agentId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AgentDocument upload(@PathVariable UUID agentId, @RequestParam("file") MultipartFile file) {
        requireAgent(agentId);

        if (file.isEmpty()) {
            throw ApiException.badRequest("the uploaded file is empty");
        }
        // No size check here: spring.servlet.multipart.max-file-size is enforced by the
        // container before this method runs, so a second limit could only disagree with it.
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".pdf")) {
            throw ApiException.badRequest("only PDF files are supported for now");
        }

        // Spool to disk and parse from there. getBytes() would put the whole upload on
        // the heap, which a large PDF turns into an OutOfMemoryError.
        Path spooled = spool(file);
        try {
            List<String> pages = extractor.extractPages(spooled);
            if (pages.stream().allMatch(String::isBlank)) {
                // Almost always a scan: images of text, with no text layer to extract.
                throw ApiException.badRequest("no text could be extracted; a scanned PDF "
                        + "needs OCR before it can be searched");
            }

            return documents.insert(agentId, filename,
                    file.getContentType() == null ? "application/pdf" : file.getContentType(),
                    file.getSize(), pages);
        } finally {
            try {
                Files.deleteIfExists(spooled);
            } catch (IOException e) {
                log.warn("could not delete the spooled upload {}", spooled, e);
            }
        }
    }

    /**
     * Moves the upload to a file this method owns. Spring's own temp file is tied to the
     * request and may already be gone by the time PDFBox reopens it.
     */
    private static Path spool(MultipartFile file) {
        Path spooled;
        try {
            spooled = Files.createTempFile("agent-upload-", ".pdf");
        } catch (IOException e) {
            throw new IllegalStateException("could not create a temp file for the upload", e);
        }
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, spooled, StandardCopyOption.REPLACE_EXISTING);
            return spooled;
        } catch (IOException e) {
            try {
                Files.deleteIfExists(spooled);
            } catch (IOException ignored) {
                // Losing a temp file matters less than reporting the real failure.
            }
            throw ApiException.badRequest("could not read the upload: " + e.getMessage());
        }
    }

    @DeleteMapping("/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID agentId, @PathVariable UUID documentId) {
        requireAgent(agentId);
        if (!documents.delete(agentId, documentId)) {
            throw ApiException.notFound("document not found: " + documentId);
        }
    }

    private void requireAgent(UUID agentId) {
        if (agents.findById(agentId).isEmpty()) {
            throw ApiException.notFound("agent not found: " + agentId);
        }
    }
}
