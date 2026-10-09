package com.smartup24.cms.instance.mf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.instance.mf.storage.LocalStorageProvider;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The local disk store: a stored file reads back as written, and a failed write leaves no partial file. */
class LocalStorageProviderLifecycleTest {

    private static final byte[] CONTENT = "SmartupCMS local file".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path storagePath;

    @Test
    @DisplayName("Upload, exists, download and delete agree on one object and its SHA-256")
    void lifecycle() throws Exception {
        var provider = new LocalStorageProvider(storagePath.toString());

        var stored = provider.upload("files", "2026/a.txt", new ByteArrayInputStream(CONTENT), CONTENT.length, "x");

        assertThat(stored.sizeBytes()).isEqualTo(CONTENT.length);
        assertThat(stored.sha256())
                .isEqualTo(HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256").digest(CONTENT)));
        assertThat(provider.exists("files", "2026/a.txt")).isTrue();
        try (var download = provider.download("files", "2026/a.txt")) {
            assertThat(download.inputStream().readAllBytes()).isEqualTo(CONTENT);
            assertThat(download.contentLength()).isEqualTo(CONTENT.length);
            assertThat(download.contentType()).isNotBlank();
        }

        provider.delete("files", "2026/a.txt");
        provider.delete("files", "2026/a.txt");

        assertThat(provider.exists("files", "2026/a.txt")).isFalse();
        assertThat(provider.download("files", "2026/a.txt")).isNull();
        assertThat(provider.getProviderCode()).isEqualTo("local_disk");
        assertThat(provider.checkHealth().isHealthy()).isTrue();
    }

    @Test
    @DisplayName("A source that fails mid-way and a short source both leave no partial file")
    void failedWriteLeavesNoPartialFile() {
        var provider = new LocalStorageProvider(storagePath.toString());
        InputStream broken = new InputStream() {
            private int left = 3;

            @Override
            public int read() throws IOException {
                if (left-- > 0) {
                    return 'x';
                }
                throw new IOException("network dropped");
            }
        };

        assertThatThrownBy(() -> provider.upload("files", "broken.txt", broken, 10, "text/plain"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Failed to upload file to storage")
                .hasCauseInstanceOf(IOException.class);
        assertThat(provider.exists("files", "broken.txt")).isFalse();

        assertThatThrownBy(() -> provider.upload(
                        "files", "short.txt", new ByteArrayInputStream(CONTENT), CONTENT.length + 5L, "text/plain"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(provider.exists("files", "short.txt")).isFalse();
    }

    @Test
    @DisplayName("Missing content, a negative size and a blank address are refused")
    void invalidRequestsAreRefused() {
        var provider = new LocalStorageProvider(storagePath.toString());

        assertThatThrownBy(() -> provider.upload("files", "a.txt", null, 1, "text/plain"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> provider.upload("files", "a.txt", new ByteArrayInputStream(CONTENT), -1, "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> provider.exists(" ", "a.txt")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> provider.exists("files", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("A storage path that cannot be a directory stops the start")
    void unusableStoragePathStopsTheStart() throws IOException {
        Path file = Files.writeString(storagePath.resolve("not-a-directory"), "x");

        assertThatThrownBy(() -> new LocalStorageProvider(file.resolve("store").toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Could not initialize local storage path");
    }

    @Test
    @DisplayName("A directory where the object should be is a storage failure, not a missing file")
    void directoryInPlaceOfObjectIsAFailure() throws IOException {
        var provider = new LocalStorageProvider(storagePath.toString());
        Files.createDirectories(storagePath.resolve("files").resolve("dir.txt").resolve("child"));

        assertThatThrownBy(() -> provider.download("files", "dir.txt"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Failed to download file from storage");
        assertThatThrownBy(() -> provider.delete("files", "dir.txt"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Failed to delete file from storage");
    }
}
