package com.getjobs.cloud.resume;

import com.getjobs.cloud.web.ApiException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResumeFileValidatorTest {
    private final ResumeFileValidator validator = new ResumeFileValidator();

    @Test
    void acceptsRealPdfDocxAndChineseTextEncodings() throws Exception {
        assertThat(validator.validate(pdf(), "resume.pdf", "application/pdf").contentType())
                .isEqualTo("application/pdf");
        assertThat(validator.validate(docx(), "resume.docx", "application/octet-stream").contentType())
                .contains("wordprocessingml");
        byte[] gb18030 = "中文简历".getBytes(Charset.forName("GB18030"));
        assertThat(validator.validate(gb18030, "resume.txt", "text/plain").contentType())
                .isEqualTo("text/plain");
        assertThat(ResumeFileValidator.decodeText(gb18030)).isEqualTo("中文简历");
    }

    @Test
    void rejectsForgedMimeBinaryTextAndOversizedFiles() {
        assertThatThrownBy(() -> validator.validate(pdf(), "resume.pdf", "text/plain"))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo("FILE_SIGNATURE_INVALID"));
        assertThatThrownBy(() -> validator.validate(new byte[]{0, 1, 2, 3, 4}, "resume.txt", "text/plain"))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo("FILE_SIGNATURE_INVALID"));
        assertThatThrownBy(() -> validator.validate(
                new byte[ResumeFileValidator.MAX_BYTES + 1], "resume.txt", "text/plain"
        )).isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo("PAYLOAD_TOO_LARGE"));
    }

    @Test
    void sanitizesPathFromDisplayFilename() {
        ResumeFileValidator.ValidatedFile result = validator.validate(
                "hello".getBytes(StandardCharsets.UTF_8),
                "../../private/resume.txt",
                "text/plain"
        );
        assertThat(result.filename()).isEqualTo("resume.txt");
    }

    private byte[] pdf() {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(output);
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private byte[] docx() throws Exception {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("Java 工程师简历");
            document.write(output);
            return output.toByteArray();
        }
    }
}
