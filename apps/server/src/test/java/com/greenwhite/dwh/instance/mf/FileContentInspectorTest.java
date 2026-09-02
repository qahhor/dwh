package com.greenwhite.dwh.instance.mf;

import com.greenwhite.dwh.instance.mf.service.FileContentInspector;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class FileContentInspectorTest {

    private final FileContentInspector inspector = new FileContentInspector();

    @Test
    void inspectionDoesNotConsumeTheUploadStream() throws Exception {
        byte[] content = "%PDF-1.7\nbody".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

        var inspected = inspector.inspect(
                "application/pdf; charset=binary", new ByteArrayInputStream(content));

        assertThat(inspected.verifiedMimeType()).isEqualTo("application/pdf");
        assertThat(inspected.content().readAllBytes()).containsExactly(content);
    }

    @Test
    void preservesOfficeMimeForAValidZipContainer() {
        byte[] zipHeader = new byte[] {0x50, 0x4b, 0x03, 0x04, 0x14, 0x00};

        var inspected = inspector.inspect(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                new ByteArrayInputStream(zipHeader));

        assertThat(inspected.verifiedMimeType()).isEqualTo(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    }
}
