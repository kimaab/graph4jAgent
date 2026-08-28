package com.graph.graphtemp.document;

import com.graph.graphtemp.error.ApiException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Pulls a PDF apart into one string per page.
 * <p>
 * Extraction happens once, at upload: searching re-parses nothing, and an exported
 * agent needs no PDF library because it reads the text back out of the database.
 */
@Component
public class PdfTextExtractor {

    /** Guards against a pathological page dominating the stored corpus. */
    private static final int MAX_CHARS_PER_PAGE = 20_000;

    /**
     * @return page text in page order, index 0 being page 1. A page with no extractable
     *         text yields an empty string rather than disappearing, so page numbers
     *         stay aligned with what the reader sees.
     * @throws ApiException 400 when the bytes are not a PDF this library can open
     */
    public List<String> extractPages(byte[] pdf) {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            if (document.isEncrypted()) {
                throw ApiException.badRequest("the PDF is encrypted, so its text cannot be read");
            }

            PDFTextStripper stripper = new PDFTextStripper();
            int total = document.getNumberOfPages();
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
