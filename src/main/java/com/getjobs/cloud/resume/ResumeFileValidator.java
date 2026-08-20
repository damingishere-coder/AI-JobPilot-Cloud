package com.getjobs.cloud.resume;

import com.getjobs.cloud.web.ApiException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
public class ResumeFileValidator {
    public static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final int MAX_PDF_PAGES = 50;
    private static final int MAX_DOCX_ENTRIES = 1000;
    private static final long MAX_DOCX_EXPANDED_BYTES = 50L * 1024 * 1024;
    private static final String PDF = "application/pdf";
    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String TEXT = "text/plain";

    public ValidatedFile validate(byte[] content, String originalFilename, String claimedContentType) {
        if (content.length == 0) {
            throw invalid("上传文件不能为空");
        }
        if (content.length > MAX_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "简历文件不能超过 10 MiB");
        }
        String filename = sanitizeFilename(originalFilename);
        String lower = filename.toLowerCase(Locale.ROOT);
        String canonical;
        if (lower.endsWith(".pdf")) {
            validatePdf(content);
            canonical = PDF;
        } else if (lower.endsWith(".docx")) {
            validateDocx(content);
            canonical = DOCX;
        } else if (lower.endsWith(".txt")) {
            validateText(content);
            canonical = TEXT;
        } else {
            throw new ApiException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "UNSUPPORTED_MEDIA_TYPE",
                    "仅支持 PDF、DOCX 和 TXT 简历"
            );
        }
        validateClaimedType(canonical, claimedContentType);
        return new ValidatedFile(filename, canonical);
    }

    public static String decodeText(byte[] content) {
        if (hasPrefix(content, new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF})) {
            return decodeStrict(content, 3, StandardCharsets.UTF_8);
        }
        if (hasPrefix(content, new byte[]{(byte) 0xFF, (byte) 0xFE})) {
            return decodeStrict(content, 2, StandardCharsets.UTF_16LE);
        }
        if (hasPrefix(content, new byte[]{(byte) 0xFE, (byte) 0xFF})) {
            return decodeStrict(content, 2, StandardCharsets.UTF_16BE);
        }
        try {
            return decodeStrict(content, 0, StandardCharsets.UTF_8);
        } catch (ApiException ignored) {
            return decodeStrict(content, 0, Charset.forName("GB18030"));
        }
    }

    private void validatePdf(byte[] content) {
        if (!hasPrefix(content, "%PDF-".getBytes(StandardCharsets.US_ASCII))) {
            throw invalid("PDF 文件签名不正确");
        }
        try (PDDocument document = Loader.loadPDF(content)) {
            if (document.getNumberOfPages() == 0 || document.getNumberOfPages() > MAX_PDF_PAGES) {
                throw invalid("PDF 页数必须在 1 到 50 页之间");
            }
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid("PDF 已加密、损坏或无法安全读取");
        }
    }

    private void validateDocx(byte[] content) {
        if (!hasPrefix(content, new byte[]{'P', 'K'})) {
            throw invalid("DOCX 文件签名不正确");
        }
        int entries = 0;
        long expanded = 0;
        Set<String> names = new HashSet<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                entries++;
                if (entries > MAX_DOCX_ENTRIES) {
                    throw invalid("DOCX 内部文件数量过多");
                }
                String name = entry.getName().replace('\\', '/');
                if (name.startsWith("/") || name.contains("../")) {
                    throw invalid("DOCX 包含不安全路径");
                }
                names.add(name);
                int read;
                while ((read = zip.read(buffer)) >= 0) {
                    expanded += read;
                    if (expanded > MAX_DOCX_EXPANDED_BYTES) {
                        throw invalid("DOCX 解压后内容过大");
                    }
                }
            }
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid("DOCX 已加密、损坏或无法安全读取");
        }
        if (!names.contains("[Content_Types].xml") || !names.contains("word/document.xml")) {
            throw invalid("文件不是有效的 DOCX 文档");
        }
    }

    private void validateText(byte[] content) {
        String decoded = decodeText(content);
        long unsafeControls = decoded.chars()
                .filter(value -> value == 0 || (value < 32 && value != '\n' && value != '\r' && value != '\t'))
                .count();
        if (unsafeControls > Math.max(2, decoded.length() / 100)) {
            throw invalid("TXT 文件包含二进制或控制字符");
        }
    }

    private void validateClaimedType(String canonical, String claimed) {
        if (claimed == null || claimed.isBlank()) {
            return;
        }
        String normalized = claimed.toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
        if (!normalized.equals("application/octet-stream") && !normalized.equals(canonical)) {
            throw invalid("文件扩展名、内容和 MIME 类型不一致");
        }
    }

    private static String decodeStrict(byte[] content, int offset, Charset charset) {
        try {
            return charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content, offset, content.length - offset))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw invalid("TXT 编码无法识别，请使用 UTF-8、UTF-16 或 GB18030");
        }
    }

    private static String sanitizeFilename(String original) {
        String value = original == null || original.isBlank() ? "resume" : original;
        value = value.replace('\\', '/');
        value = value.substring(value.lastIndexOf('/') + 1)
                .replaceAll("[\\p{Cntrl}]", "")
                .trim();
        if (value.isEmpty()) {
            value = "resume";
        }
        return value.length() <= 255 ? value : value.substring(value.length() - 255);
    }

    private static boolean hasPrefix(byte[] content, byte[] prefix) {
        if (content.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (content[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "FILE_SIGNATURE_INVALID", message);
    }

    public record ValidatedFile(String filename, String contentType) {
    }
}
