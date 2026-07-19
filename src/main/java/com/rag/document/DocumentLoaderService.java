package com.rag.document;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.loader.UrlDocumentLoader;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import dev.langchain4j.data.document.parser.apache.poi.ApachePoiDocumentParser;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.util.Iterator;
import java.util.StringJoiner;

@Slf4j
@Service
public class DocumentLoaderService {

    private final ApachePdfBoxDocumentParser pdfParser = new ApachePdfBoxDocumentParser();
    private final ApachePoiDocumentParser poiParser     = new ApachePoiDocumentParser();

    // ── PDF ──────────────────────────────────────────────────────────────────
    public Document loadPdf(MultipartFile file) throws IOException {
        log.info("Loading PDF: {}", file.getOriginalFilename());
        try (InputStream is = file.getInputStream()) {
            Document doc = pdfParser.parse(is);
            return Document.from(doc.text(),
                    Metadata.from("source", file.getOriginalFilename())
                            .put("type", "PDF")
                            .put("category", DocumentCategoryClassifier.classify(file.getOriginalFilename(), DocumentType.PDF)));
        }
    }

    // ── DOCX ─────────────────────────────────────────────────────────────────
    public Document loadDocx(MultipartFile file) throws IOException {
        log.info("Loading DOCX: {}", file.getOriginalFilename());
        try (InputStream is = file.getInputStream()) {
            Document doc = poiParser.parse(is);
            return Document.from(doc.text(),
                    Metadata.from("source", file.getOriginalFilename())
                            .put("type", "DOCX")
                            .put("category", DocumentCategoryClassifier.classify(file.getOriginalFilename(), DocumentType.DOCX)));
        }
    }

    // ── Excel (XLSX / XLS) ───────────────────────────────────────────────────
    // NOTE: IngestionService no longer calls these two methods for the main ingestion path —
    // it goes straight to com.rag.document.excel.ExcelStructuredChunker, which preserves the
    // Workbook/Sheet/Row/Cell structure and per-row metadata instead of flattening everything
    // into one pipe-separated blob. These are kept for any caller that still wants a flat-text
    // rendering of a whole workbook (e.g. a quick preview).
    public Document loadExcel(MultipartFile file) throws IOException {
        log.info("Loading Excel: {}", file.getOriginalFilename());
        String filename = file.getOriginalFilename() != null
                ? file.getOriginalFilename().toLowerCase() : "";

        try (InputStream is = file.getInputStream()) {
            //  set value to upload bigger size of excel

            Workbook workbook = filename.endsWith(".xls")
                    ? new HSSFWorkbook(is)
                    : new XSSFWorkbook(is);

            StringBuilder content = new StringBuilder();
            for (Sheet sheet : workbook) {
                content.append("=== Sheet: ").append(sheet.getSheetName()).append(" ===\n");
                for (Row row : sheet) {
                    StringJoiner rowJoiner = new StringJoiner(" | ");
                    for (Cell cell : row) {
                        rowJoiner.add(getCellValue(cell));
                    }
                    content.append(rowJoiner).append("\n");
                }
                content.append("\n");
            }
            workbook.close();

            return Document.from(content.toString(),
                    Metadata.from("source", file.getOriginalFilename())
                            .put("type", "EXCEL")
                            .put("category", DocumentCategoryClassifier.CATALOG));
        }
    }

    // ── Web URL ──────────────────────────────────────────────────────────────
    public Document loadUrl(String url) throws IOException {
        log.info("Loading URL: {}", url);
        org.jsoup.nodes.Document jsoupDoc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0")
                .timeout(15_000)
                .get();

        String text = jsoupDoc.body().text();
        return Document.from(text,
                Metadata.from("source", url)
                        .put("type", "URL")
                        .put("title", jsoupDoc.title())
                        .put("category", DocumentCategoryClassifier.GENERAL));
    }

    // ── TXT / Plain text ─────────────────────────────────────────────────────
    public Document loadText(MultipartFile file) throws IOException {
        log.info("Loading text file: {}", file.getOriginalFilename());
        String text = new String(file.getBytes());
        return Document.from(text,
                Metadata.from("source", file.getOriginalFilename())
                        .put("type", "TXT")
                        .put("category", DocumentCategoryClassifier.classify(file.getOriginalFilename(), DocumentType.TXT)));
    }

    // ── Dispatcher ───────────────────────────────────────────────────────────
    public Document load(MultipartFile file) throws IOException {
        DocumentType type = DocumentType.fromFilename(file.getOriginalFilename());
        return switch (type) {
            case PDF   -> loadPdf(file);
            case DOCX  -> loadDocx(file);
            case EXCEL -> loadExcel(file);
            default    -> loadText(file);
        };
    }

    // ── Disk-based loading (used for folder / batch ingestion) ──────────────
    public Document loadPdf(File file) throws IOException {
        log.info("Loading PDF from disk: {}", file.getName());
        try (InputStream is = new FileInputStream(file)) {
            Document doc = pdfParser.parse(is);
            return Document.from(doc.text(),
                    Metadata.from("source", file.getAbsolutePath())
                            .put("type", "PDF")
                            .put("category", DocumentCategoryClassifier.classify(file.getName(), DocumentType.PDF)));
        }
    }

    public Document loadDocx(File file) throws IOException {
        log.info("Loading DOCX from disk: {}", file.getName());
        try (InputStream is = new FileInputStream(file)) {
            Document doc = poiParser.parse(is);
            return Document.from(doc.text(),
                    Metadata.from("source", file.getAbsolutePath())
                            .put("type", "DOCX")
                            .put("category", DocumentCategoryClassifier.classify(file.getName(), DocumentType.DOCX)));
        }
    }

    public Document loadExcel(File file) throws IOException {
        log.info("Loading Excel from disk: {}", file.getName());
        String filename = file.getName().toLowerCase();

        try (InputStream is = new FileInputStream(file)) {
            Workbook workbook = filename.endsWith(".xls")
                    ? new HSSFWorkbook(is)
                    : new XSSFWorkbook(is);

            StringBuilder content = new StringBuilder();
            for (Sheet sheet : workbook) {
                content.append("=== Sheet: ").append(sheet.getSheetName()).append(" ===\n");
                for (Row row : sheet) {
                    StringJoiner rowJoiner = new StringJoiner(" | ");
                    for (Cell cell : row) {
                        rowJoiner.add(getCellValue(cell));
                    }
                    content.append(rowJoiner).append("\n");
                }
                content.append("\n");
            }
            workbook.close();

            return Document.from(content.toString(),
                    Metadata.from("source", file.getAbsolutePath())
                            .put("type", "EXCEL")
                            .put("category", DocumentCategoryClassifier.CATALOG));
        }
    }

    public Document loadText(File file) throws IOException {
        log.info("Loading text file from disk: {}", file.getName());
        String text = Files.readString(file.toPath());
        return Document.from(text,
                Metadata.from("source", file.getAbsolutePath())
                        .put("type", "TXT")
                        .put("category", DocumentCategoryClassifier.classify(file.getName(), DocumentType.TXT)));
    }

    // ── Dispatcher for disk files (folder ingestion) ─────────────────────────
    public Document load(File file) throws IOException {
        DocumentType type = DocumentType.fromFilename(file.getName());
        return switch (type) {
            case PDF   -> loadPdf(file);
            case DOCX  -> loadDocx(file);
            case EXCEL -> loadExcel(file);
            default    -> loadText(file);
        };
    }

    // ── Helpers ──────────────────────────────────────────────────────────────
    private String getCellValue(Cell cell) {
        return switch (cell.getCellType()) {
            case STRING  -> cell.getStringCellValue();
            case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                    ? cell.getDateCellValue().toString()
                    : String.valueOf(cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> cell.getCellFormula();
            default      -> "";
        };
    }
}
