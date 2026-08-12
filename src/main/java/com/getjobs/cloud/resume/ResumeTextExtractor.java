package com.getjobs.cloud.resume;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;

@Component
@Profile("worker")
public class ResumeTextExtractor {
    private static final int MAX_TEXT_CHARACTERS = 200_000;

    public String extract(byte[] content, String contentType) {
        String text = switch (contentType) {
            case "application/pdf" -> extractPdf(content);
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> extractDocx(content);
            case "text/plain" -> ResumeFileValidator.decodeText(content);
            default -> throw new ResumeParseException("不支持的简历格式");
        };
        String normalized = text
                .replace("\u0000", "")
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim();
        if (normalized.isBlank()) {
            throw new ResumeParseException("未检测到可提取文字，扫描版简历暂不支持 OCR");
        }
        return normalized.length() <= MAX_TEXT_CHARACTERS
                ? normalized
                : normalized.substring(0, MAX_TEXT_CHARACTERS);
    }

    private String extractPdf(byte[] content) {
        try (PDDocument document = Loader.loadPDF(content)) {
            return new PDFTextStripper().getText(document);
        } catch (Exception exception) {
            throw new ResumeParseException("PDF 文本提取失败，请确认文件未加密且可以正常打开", exception);
        }
    }

    private String extractDocx(byte[] content) {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(content));
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        } catch (Exception exception) {
            throw new ResumeParseException("DOCX 文本提取失败，请重新导出后上传", exception);
        }
    }
}
