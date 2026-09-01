package com.graph.graphtemp.document;

import com.graph.graphtemp.error.ApiException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Pulls a PDF apart into one string per page.
 * <p>
 * Reads from a file rather than a byte array, and tells PDFBox to spill its own working
 * data to temp files. A large upload therefore costs disk, not heap: holding a 300MB
 * document in memory and then parsing it there would trade a clean rejection for an
 * OutOfMemoryError that takes the whole server with it.
 * <p>
 * Extraction happens once, at upload: searching re-parses nothing, and an exported agent
 * needs no PDF library because it reads the text back out of the database.
 */
@Component
public class PdfTextExtractor {

    /** Guards against a pathological page dominating the stored corpus. */
    private static final int MAX_CHARS_PER_PAGE = 20_000;

    /**
     * The extracted text is held in memory before it is stored, so page count is what
     * bounds that. At the per-page cap above, this is roughly 200MB of text — already
     * far more than any agent can read.
     */
    private static final int MAX_PAGES = 5_000;

    /**
     * @return page text in page order, index 0 being page 1. A page with no extractable
     *         text yields an empty string rather than disappearing, so page numbers stay
     *         aligned with what the reader sees.
     * @throws ApiException 400 when the bytes are not a PDF this library can open
     */
    public List<String> extractPages(Path pdf) {
        try (PDDocument document = Loader.loadPDF(pdf.toFile(),
                IOUtils.createTempFileOnlyStreamCache())) {

            if (document.isEncrypted()) {
                throw ApiException.badRequest("the PDF is encrypted, so its text cannot be read");
            }

            int total = document.getNumberOfPages();
            if (total > MAX_PAGES) {
                throw ApiException.badRequest("the PDF has " + total + " pages; the limit is "
                        + MAX_PAGES + ". Split it and upload the parts separately");
            }

            PDFTextStripper stripper = new PDFTextStripper();
            List<String> pages = new ArrayList<>(total);

            for (int page = 1; page <= total; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                pages.add(trim(stripper.getText(document)));
            }
            return pages;
        } catch (IOException e) {
            throw ApiException.badRequest("could not read the PDF: " + e.getMessage());
        }
    }

    private static String trim(String text) {
        String cleaned = text == null ? "" : text.strip();
        return cleaned.length() <= MAX_CHARS_PER_PAGE
                ? cleaned
                : cleaned.substring(0, MAX_CHARS_PER_PAGE);
    }
}
