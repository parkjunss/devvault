package org.eardream.devvault.lesson.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.stream.Collectors;

@Service
public class TextExtractionService {

    public String extract(Path path, String contentType, String originalName) {
        String extension = extensionOf(originalName);
        try {
            if (isPdf(contentType, extension)) {
                return extractPdf(path);
            }
            if (isDocx(contentType, extension)) {
                return extractDocx(path);
            }
            if (isPlainText(contentType, extension)) {
                return Files.readString(path, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "파일에서 텍스트를 추출하지 못했습니다.", e);
        }
        throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "지원하지 않는 파일 형식입니다. PDF, DOCX, MD, TXT만 지원합니다.");
    }

    private String extractPdf(Path path) throws IOException {
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            return new PDFTextStripper().getText(document);
        }
    }

    private String extractDocx(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path);
             XWPFDocument document = new XWPFDocument(input)) {
            return document.getParagraphs().stream()
                    .map(XWPFParagraph::getText)
                    .collect(Collectors.joining("\n"));
        }
    }

    private static boolean isPdf(String contentType, String extension) {
        return "application/pdf".equalsIgnoreCase(contentType) || "pdf".equals(extension);
    }

    private static boolean isDocx(String contentType, String extension) {
        return "application/vnd.openxmlformats-officedocument.wordprocessingml.document".equalsIgnoreCase(contentType)
                || "docx".equals(extension);
    }

    private static boolean isPlainText(String contentType, String extension) {
        return "md".equals(extension) || "txt".equals(extension) || "markdown".equals(extension)
                || (contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("text/"));
    }

    private static String extensionOf(String originalName) {
        if (originalName == null) {
            return "";
        }
        int dot = originalName.lastIndexOf('.');
        return dot < 0 ? "" : originalName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
