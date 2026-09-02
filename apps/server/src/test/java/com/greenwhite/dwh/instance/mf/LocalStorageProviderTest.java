package com.greenwhite.dwh.instance.mf;

import com.greenwhite.dwh.instance.mf.storage.LocalStorageProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalStorageProviderTest {

    @TempDir
    Path storagePath;

    @Test
    void rejectsTraversalAcrossEveryOperation() {
        var provider = new LocalStorageProvider(storagePath.toString());

        assertThatThrownBy(() -> provider.upload(
                "../outside", "file.txt", new ByteArrayInputStream(new byte[]{1}), 1, "text/plain"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> provider.download("../outside", "file.txt"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> provider.exists("../outside", "file.txt"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> provider.delete("../outside", "file.txt"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsContentWhoseLengthDiffersFromDeclaredSize() {
        var provider = new LocalStorageProvider(storagePath.toString());

        assertThatThrownBy(() -> provider.upload(
                "instance-files", "file.txt", new ByteArrayInputStream(new byte[]{1, 2}), 1, "text/plain"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("size");
    }
}
