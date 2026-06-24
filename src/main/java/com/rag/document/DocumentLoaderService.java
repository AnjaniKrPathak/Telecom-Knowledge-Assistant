package com.rag.document;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.loader.UrlDocumentLoader;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import dev.langchain4j.data.document.parser.apache.poi.ApachePoiDocumentParser;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
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
                            .put("type", "PDF"));
        }
    }

    // ── DOCX ─────────────────────────────────────────────────────────────────
    public Document loadDocx(MultipartFile file) throws IOException {
        log.info("Loading DOCX: {}", file.getOriginalFilename());
        try (InputStream is = file.getInputStream()) {
            Document doc = poiParser.parse(is);
            return Document.from(doc.text(),
                    Metadata.from("source", file.getOriginalFilename())
                            .put("type", "DOCX"));
        }
    }

    // ── Excel (XLSX / XLS) ───────────────────────────────────────────────────
    public Document loadExcel(MultipartFile file) throws IOException {
        log.info("Loading Excel: {}", file.getOriginalFilename());
        String filename = file.getOriginalFilename() != null
                ? file.getOriginalFilename().toLowerCase() : "";

        try (InputStream is = file.getInputStream()) {
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
                            .put("type", "EXCEL"));
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
                        .put("title", jsoupDoc.title()));
    }

    // ── TXT / Plain text ─────────────────────────────────────────────────────
    public Document loadText(MultipartFile file) throws IOException {
        log.info("Loading text file: {}", file.getOriginalFilename());
        String text = new String(file.getBytes());
        return Document.from(text,
                Metadata.from("source", file.getOriginalFilename())
                        .put("type", "TXT"));
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
