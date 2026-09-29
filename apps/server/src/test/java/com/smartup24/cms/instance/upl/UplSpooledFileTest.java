package com.smartup24.cms.instance.upl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.instance.upl.parse.UplSpooledFile;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Plan 10/10, item 3.9: the package file is spooled to disk and never left behind. */
class UplSpooledFileTest {

    @Test
    void spooledFileHoldsTheContentAndIsDeletedOnClose() throws IOException {
        Path path;
        try (UplSpooledFile spooled = UplSpooledFile.of(new ByteArrayInputStream(new byte[] {1, 2, 3}))) {
            path = spooled.path();
            assertThat(Files.readAllBytes(path)).containsExactly(1, 2, 3);
        }
        assertThat(path).doesNotExist();
    }

    @Test
    void aFailedCopyLeavesNoFileBehind() {
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("the storage stream broke");
            }
        };

        assertThatThrownBy(() -> UplSpooledFile.of(broken)).isInstanceOf(IOException.class);
    }
}
