package com.greenwhite.dwh.instance.mf;

import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.mf.repository.MfFileRepository;
import com.greenwhite.dwh.instance.mf.service.MfFileService;
import com.greenwhite.dwh.spi.storage.StorageProvider;
import com.greenwhite.dwh.spi.storage.StoredFileMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

class MfFileServiceTest {

    private final MfFileRepository fileRepository = Mockito.mock(MfFileRepository.class);
    private final StorageProvider storageProvider = Mockito.mock(StorageProvider.class);
    private final MfFileService service = new MfFileService(fileRepository, storageProvider);

    @Test
    @DisplayName("Загрузка исполняемых файлов (.exe, .sh, .bat) должна отклоняться политикой безопасности")
    void shouldRejectForbiddenFileExtensions() {
        byte[] content = "echo dangerous".getBytes();

        assertThatThrownBy(() -> service.uploadFile("malicious.sh", "text/plain", new ByteArrayInputStream(content), content.length, 1L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Загрузка исполняемых файлов (.sh) запрещена");
    }

    @Test
    @DisplayName("Дедупликация файлов: повторная загрузка файла с тем же SHA-256 должна возвращать существующую запись")
    void shouldDeduplicateIdenticalFiles() {
        String sha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        var existingFile = new MfFileRepository.FileRecord(
                UUID.randomUUID(), sha256, "report.pdf", 1024, "application/pdf",
                "instance-files", "e3/" + sha256, Instant.now(), 1L
        );

        when(storageProvider.upload(anyString(), anyString(), any(), anyLong(), anyString()))
                .thenReturn(new StoredFileMetadata("instance-files", "temp_1", sha256, 1024, "application/pdf", Instant.now()));
        when(fileRepository.findBySha256(sha256)).thenReturn(Optional.of(existingFile));

        var result = service.uploadFile("report_copy.pdf", "application/pdf", new ByteArrayInputStream(new byte[1024]), 1024, 1L);

        assertThat(result.id()).isEqualTo(existingFile.id());
        assertThat(result.sha256()).isEqualTo(sha256);
    }
}
