package com.greenwhite.dwh.instance.mf;

import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.mf.repository.MfFileRepository;
import com.greenwhite.dwh.instance.mf.service.MfFileService;
import com.greenwhite.dwh.instance.mf.service.FileContentInspector;
import com.greenwhite.dwh.spi.storage.StorageProvider;
import com.greenwhite.dwh.spi.storage.StoredFileMetadata;
import com.greenwhite.dwh.spi.storage.FileDownloadStream;
import com.greenwhite.dwh.spi.storage.FileScanner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DuplicateKeyException;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

class MfFileServiceTest {

    private final MfFileRepository fileRepository = Mockito.mock(MfFileRepository.class);
    private final StorageProvider storageProvider = Mockito.mock(StorageProvider.class);
    private final MfFileService service = new MfFileService(
            fileRepository,
            storageProvider,
            Mockito.mock(com.greenwhite.dwh.instance.audit.service.AuditLogService.class),
            new FileContentInspector(),
            List.of());

    @Test
    @DisplayName("Загрузка исполняемых файлов (.exe, .sh, .bat) должна отклоняться политикой безопасности")
    void shouldRejectForbiddenFileExtensions() {
        byte[] content = "echo dangerous".getBytes();

        assertThatThrownBy(() -> service.uploadFile("malicious.sh", "text/plain", new ByteArrayInputStream(content), content.length, 1L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Загрузка исполняемых файлов (.sh) запрещена");
    }

    @Test
    @DisplayName("Исполняемый PE-файл нельзя скрыть под именем и MIME PDF")
    void shouldRejectExecutableSignatureBeforeStorageUpload() {
        byte[] disguisedExecutable = new byte[] {
                0x4d, 0x5a, (byte) 0x90, 0x00, 0x03, 0x00, 0x00, 0x00
        };
        givenRoomInQuotas(1L);

        assertThatThrownBy(() -> service.uploadFile(
                "invoice.pdf",
                "application/pdf",
                new ByteArrayInputStream(disguisedExecutable),
                disguisedExecutable.length,
                1L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("исполняемого файла");

        Mockito.verify(storageProvider, Mockito.never())
                .upload(anyString(), anyString(), any(), anyLong(), anyString());
    }

    @Test
    @DisplayName("Заражённый объект удаляется из карантина и не публикуется в БД")
    void infectedObjectIsDeletedFromQuarantineAndNeverPublished() {
        byte[] content = pdfBytes(128);
        FileScanner scanner = Mockito.mock(FileScanner.class);
        MfFileService scanningService = new MfFileService(
                fileRepository,
                storageProvider,
                Mockito.mock(com.greenwhite.dwh.instance.audit.service.AuditLogService.class),
                new FileContentInspector(),
                List.of(scanner));
        givenRoomInQuotas(1L);
        when(storageProvider.upload(anyString(), anyString(), any(), anyLong(), anyString()))
                .thenReturn(new StoredFileMetadata(
                        "instance-files", "temp_scan", SHA, content.length,
                        "application/pdf", Instant.now()));
        when(storageProvider.download(eq("instance-files"), startsWith("temp_")))
                .thenReturn(new FileDownloadStream(
                        new ByteArrayInputStream(content), content.length, "application/pdf"));
        when(scanner.scan(any(), eq((long) content.length), eq("application/pdf")))
                .thenReturn(FileScanner.ScanResult.infected("EICAR-Test-Signature"));

        assertThatThrownBy(() -> scanningService.uploadFile(
                "invoice.pdf",
                "application/pdf",
                new ByteArrayInputStream(content),
                content.length,
                1L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("вредоносное содержимое");

        Mockito.verify(storageProvider).delete(eq("instance-files"), startsWith("temp_"));
        Mockito.verify(fileRepository, Mockito.never())
                .create(anyString(), anyString(), anyLong(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("Временный объект удаляется из карантина при ошибке публикации метаданных")
    void temporaryObjectIsDeletedWhenMetadataPublicationFails() {
        byte[] content = pdfBytes(128);
        givenRoomInQuotas(1L);
        when(fileRepository.findBySha256AndOwner(SHA, 1L))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> service.uploadFile(
                "invoice.pdf",
                "application/pdf",
                new ByteArrayInputStream(content),
                content.length,
                1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("database unavailable");

        Mockito.verify(storageProvider).delete(eq("instance-files"), startsWith("temp_"));
    }

    private static final String SHA = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private void givenRoomInQuotas(Long userId) {
        when(fileRepository.getCompanyQuotaBytes()).thenReturn(50L * 1024 * 1024 * 1024);
        when(fileRepository.getTotalCompanyUsedBytes()).thenReturn(0L);
        when(fileRepository.getUserEffectiveQuotaBytes(userId)).thenReturn(1024L * 1024 * 1024);
        when(fileRepository.getUserUsedBytes(userId)).thenReturn(0L);
        when(storageProvider.upload(anyString(), anyString(), any(), anyLong(), anyString()))
                .thenReturn(new StoredFileMetadata("instance-files", "temp_1", SHA, 1024, "application/pdf", Instant.now()));
    }

    private static MfFileRepository.FileRecord record(String name, Long owner) {
        return new MfFileRepository.FileRecord(
                UUID.randomUUID(), SHA, name, 1024, "application/pdf",
                "instance-files", "e3/" + SHA, Instant.now(), owner);
    }

    @Test
    @DisplayName("Повторная загрузка своего же файла возвращает прежнюю запись и не списывает квоту дважды")
    void shouldReuseOwnRecordOnRepeatedUpload() {
        var own = record("report.pdf", 1L);
        givenRoomInQuotas(1L);
        when(fileRepository.findBySha256AndOwner(SHA, 1L)).thenReturn(Optional.of(own));

        var result = service.uploadFile("report_copy.pdf", "application/pdf",
                new ByteArrayInputStream(pdfBytes(1024)), 1024, 1L);

        assertThat(result.id()).isEqualTo(own.id());
        Mockito.verify(fileRepository, Mockito.never())
                .create(anyString(), anyString(), anyLong(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("Тот же файл от другого пользователя даёт СВОЮ запись владения, а не чужую (Д-1)")
    void shouldCreateOwnRecordWhenContentBelongsToAnotherUser() {
        var foreign = record("dogovor_A.pdf", 1L);
        givenRoomInQuotas(2L);
        when(fileRepository.findBySha256AndOwner(SHA, 2L)).thenReturn(Optional.empty());
        when(fileRepository.findBySha256(SHA)).thenReturn(Optional.of(foreign));
        when(fileRepository.create(anyString(), anyString(), anyLong(), anyString(), anyString(), anyString(), any()))
                .thenAnswer(inv -> record(inv.getArgument(1), inv.getArgument(6)));

        var result = service.uploadFile("my_copy.pdf", "application/pdf",
                new ByteArrayInputStream(pdfBytes(1024)), 1024, 2L);

        assertThat(result.id()).isNotEqualTo(foreign.id());
        assertThat(result.createdBy()).isEqualTo(2L);
        assertThat(result.originalName()).isEqualTo("my_copy.pdf");
        // Содержимое уже на диске — повторно не заливаем
        Mockito.verify(storageProvider, Mockito.never())
                .upload(eq("instance-files"), eq("e3/" + SHA), any(), anyLong(), anyString());
    }

    @Test
    @DisplayName("Удаление не трогает объект на диске, пока у содержимого остались владельцы (Д-2)")
    void shouldKeepPhysicalObjectWhileOtherOwnersRemain() {
        var mine = record("my_copy.pdf", 2L);
        when(fileRepository.findById(mine.id())).thenReturn(Optional.of(mine));
        when(fileRepository.existsBySha256(SHA)).thenReturn(true);

        service.deleteFile(mine.id(), 2L, false);

        Mockito.verify(fileRepository).delete(mine.id());
        Mockito.verify(storageProvider, Mockito.never()).delete(anyString(), anyString());
    }

    @Test
    @DisplayName("Удаление последней записи владения убирает объект с диска")
    void shouldRemovePhysicalObjectWhenLastOwnerGone() {
        var last = record("report.pdf", 1L);
        when(fileRepository.findById(last.id())).thenReturn(Optional.of(last));
        when(fileRepository.existsBySha256(SHA)).thenReturn(false);

        service.deleteFile(last.id(), 1L, false);

        Mockito.verify(storageProvider).delete("instance-files", "e3/" + SHA);
    }

    @Test
    @DisplayName("Превышение дисковой квоты компании должно блокировать загрузку (STORAGE_QUOTA_EXCEEDED)")
    void shouldRejectWhenCompanyQuotaExceeded() {
        when(fileRepository.getCompanyQuotaBytes()).thenReturn(1000L);
        when(fileRepository.getTotalCompanyUsedBytes()).thenReturn(950L);

        byte[] content = new byte[100]; // 950 + 100 = 1050 > 1000

        assertThatThrownBy(() -> service.uploadFile("data.bin", "application/octet-stream", new ByteArrayInputStream(content), content.length, 1L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Превышена дисковая квота компании");
    }

    @Test
    @DisplayName("Превышение персональной квоты пользователя должно блокировать загрузку (USER_STORAGE_QUOTA_EXCEEDED)")
    void shouldRejectWhenUserQuotaExceeded() {
        when(fileRepository.getCompanyQuotaBytes()).thenReturn(10_000_000L);
        when(fileRepository.getTotalCompanyUsedBytes()).thenReturn(0L);
        when(fileRepository.getUserEffectiveQuotaBytes(2L)).thenReturn(500L);
        when(fileRepository.getUserUsedBytes(2L)).thenReturn(450L);

        byte[] content = pdfBytes(100); // 450 + 100 = 550 > 500

        assertThatThrownBy(() -> service.uploadFile("my_doc.pdf", "application/pdf", new ByteArrayInputStream(content), content.length, 2L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Превышена ваша персональная дисковая квота");
    }

    @Test
    @DisplayName("Двойная отправка одного файла одним пользователем не падает, а возвращает запись (Д-3)")
    void shouldResolveConcurrentDuplicateUpload() {
        var winner = record("report.pdf", 3L);
        givenRoomInQuotas(3L);
        // Первый запрос успел вставить строку между нашей проверкой и вставкой
        when(fileRepository.findBySha256AndOwner(SHA, 3L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(fileRepository.findBySha256(SHA)).thenReturn(Optional.empty());
        // Объект уже на диске — заливать нечего, интересна только вставка строки
        when(storageProvider.exists(anyString(), anyString())).thenReturn(true);
        when(fileRepository.create(anyString(), anyString(), anyLong(), anyString(), anyString(), anyString(), any()))
                .thenThrow(new DuplicateKeyException("mf_files_owner_sha256_uidx"));

        var result = service.uploadFile("report.pdf", "application/pdf",
                new ByteArrayInputStream(pdfBytes(1024)), 1024, 3L);

        assertThat(result.id()).isEqualTo(winner.id());
    }

    private static byte[] pdfBytes(int size) {
        byte[] content = new byte[size];
        byte[] header = "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(header, 0, content, 0, Math.min(header.length, content.length));
        return content;
    }
}
