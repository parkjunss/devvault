package org.eardream.devvault.lesson;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.eardream.devvault.lesson.service.TextExtractionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextExtractionServiceTest {
    private final TextExtractionService service = new TextExtractionService();

    @Test
    void extractsTextFromAPdf(@TempDir Path tempDir) throws IOException {
        Path path = tempDir.resolve("week1.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 700);
                // Standard14 Helvetica has no Korean glyphs without an embedded Unicode font,
                // so this fixture uses ASCII text purely to verify PDFBox extraction wiring.
                stream.showText("Backpropagation carries error backward through the network");
                stream.endText();
            }
            document.save(path.toFile());
        }

        String extracted = service.extract(path, "application/pdf", "week1.pdf");

        assertTrue(extracted.contains("Backpropagation carries error backward through the network"));
    }

    @Test
    void extractsTextFromADocx(@TempDir Path tempDir) throws IOException {
        Path path = tempDir.resolve("week1.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            XWPFParagraph paragraph = document.createParagraph();
            XWPFRun run = paragraph.createRun();
            run.setText("경사 하강법은 손실을 최소화한다");
            try (OutputStream out = Files.newOutputStream(path)) {
                document.write(out);
            }
        }

        String extracted = service.extract(path, "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "week1.docx");

        assertTrue(extracted.contains("경사 하강법은 손실을 최소화한다"));
    }

    @Test
    void readsMarkdownAsPlainText(@TempDir Path tempDir) throws IOException {
        Path path = tempDir.resolve("week1.md");
        Files.writeString(path, "# 1주차\n오늘 배운 것", StandardCharsets.UTF_8);

        String extracted = service.extract(path, "text/markdown", "week1.md");

        assertTrue(extracted.contains("오늘 배운 것"));
    }

    @Test
    void rejectsUnsupportedFileTypes(@TempDir Path tempDir) throws IOException {
        Path path = tempDir.resolve("week1.hwp");
        Files.write(path, new byte[]{1, 2, 3});

        assertThrows(ResponseStatusException.class,
                () -> service.extract(path, "application/x-hwp", "week1.hwp"));
    }
}
