package com.smartup24.cms.instance.mf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.ScopeFilter;
import com.smartup24.cms.instance.md.service.MdScopeService;
import com.smartup24.cms.instance.md.service.MdStorageQuotaService;
import com.smartup24.cms.instance.mf.repository.MfFileRepository;
import com.smartup24.cms.instance.mf.service.FileContentInspector;
import com.smartup24.cms.instance.mf.service.MfFileMetadataService;
import com.smartup24.cms.instance.mf.service.MfFileObjectLock;
import com.smartup24.cms.instance.mf.service.MfFileService;
import com.smartup24.cms.spi.storage.FileDownloadStream;
import com.smartup24.cms.spi.storage.FileScanner;
import com.smartup24.cms.spi.storage.StorageProvider;
import com.smartup24.cms.spi.storage.StoredFileMetadata;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The outcomes of an upload around the scanner and the store: a clean file is published under its content key, any
 * scan that gives no verdict fails closed, the quarantine is always emptied, and an object missing from the store
 * reads as not found.
 */
class MfFileServiceOutcomesTest {

    private static final String SHA = "ab" + "0".repeat(62);
    private static final String BUCKET = "instance-files";
    private static final byte[] CONTENT = pdf(256);

    private final MfFileRepository files = mock(MfFileRepository.class);
    private final StorageProvider storage = mock(StorageProvider.class);
    private final MdStorageQuotaService quotas = mock(MdStorageQuotaService.class);
    private final MdScopeService scopes = mock(MdScopeService.class);
    private final MfFileMetadataService metadata =
            new MfFileMetadataService(files, mock(AuditLogService.class), quotas);

    {
        when(scopes.filterForFiles(any())).thenReturn(ScopeFilter.unrestricted());
        when(quotas.instanceQuotaBytes()).thenReturn(1L << 40);
        when(quotas.userQuotaBytes(any())).thenReturn(1L << 30);
        when(files.getTotalCompanyUsedBytes()).thenReturn(0L);
        when(files.getUserUsedBytes(any())).thenReturn(0L);
        when(files.findBySha256AndOwner(SHA, 1L)).thenReturn(Optional.empty());
        when(files.findBySha256(SHA)).thenReturn(Optional.empty());
        when(storage.upload(anyString(), anyString(), any(), anyLong(), anyString()))
                .thenReturn(
                        new StoredFileMetadata(BUCKET, "temp", SHA, CONTENT.length, "application/pdf", Instant.now()));
        when(storage.download(eq(BUCKET), startsWith("temp_"))).thenAnswer(call -> quarantined());
    }

    @Test
    @DisplayName("A clean file is copied out of quarantine under its content key and published")
    void cleanFileIsPublished() {
        FileScanner scanner = scanner(FileScanner.ScanResult.clean());
        when(files.create(eq(SHA), eq("a.pdf"), anyLong(), anyString(), eq(BUCKET), eq("ab/" + SHA), eq(1L)))
                .thenReturn(record());

        var stored = service(List.of(scanner)).store("a.pdf", "application/pdf", content(), CONTENT.length, 1L);

        assertThat(stored.sha256()).isEqualTo(SHA);
        verify(storage).upload(eq(BUCKET), eq("ab/" + SHA), any(), eq((long) CONTENT.length), eq("application/pdf"));
        verify(storage).delete(eq(BUCKET), startsWith("temp_"));
    }

    @Test
    @DisplayName("A scanner without a verdict, a failing scanner and an unreadable quarantine all fail closed")
    void scanWithoutVerdictFailsClosed() {
        FileScanner silent = scanner(null);
        FileScanner broken = mock(FileScanner.class);
        when(broken.scan(any(), anyLong(), anyString())).thenThrow(new IllegalStateException("daemon gone"));

        for (FileScanner scanner : List.of(silent, broken)) {
            assertThatThrownBy(() -> upload(service(List.of(scanner))))
                    .isInstanceOf(ApiException.class)
                    .hasFieldOrPropertyWithValue("messageKey", "error.file.scan_incomplete");
        }
        when(storage.download(eq(BUCKET), startsWith("temp_"))).thenReturn(null);
        assertThatThrownBy(() -> upload(service(List.of(scanner(FileScanner.ScanResult.clean())))))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.file.scan_incomplete");
        verify(files, never()).create(any(), any(), anyLong(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("A quarantine that cannot be read for the copy fails the upload and is still emptied")
    void unreadableQuarantineForTheCopyFails() {
        when(storage.download(eq(BUCKET), startsWith("temp_"))).thenReturn(null);

        assertThatThrownBy(() -> upload(service(List.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Quarantined object is not readable");
        verify(storage).delete(eq(BUCKET), startsWith("temp_"));
    }

    @Test
    @DisplayName("A failed cleanup of the quarantine rides on the upload failure, and fails a clean upload alone")
    void quarantineCleanupFailure() {
        doThrow(new IllegalStateException("store busy")).when(storage).delete(eq(BUCKET), startsWith("temp_"));
        when(storage.download(eq(BUCKET), startsWith("temp_"))).thenReturn(null);

        assertThatThrownBy(() -> upload(service(List.of())))
                .hasMessageContaining("Quarantined object is not readable")
                .satisfies(failure -> assertThat(failure.getSuppressed())
                        .singleElement()
                        .satisfies(cleanup -> assertThat(cleanup).hasMessage("store busy")));

        when(storage.download(eq(BUCKET), startsWith("temp_"))).thenAnswer(call -> quarantined());
        when(storage.exists(BUCKET, "ab/" + SHA)).thenReturn(true);
        when(files.create(any(), any(), anyLong(), any(), any(), any(), any())).thenReturn(record());
        assertThatThrownBy(() -> upload(service(List.of()))).hasMessage("store busy");
    }

    @Test
    @DisplayName("A failed publication keeps an object it did not create and survives a failing cleanup")
    void failedPublicationCleanup() {
        when(files.create(any(), any(), anyLong(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("insert failed"));
        when(files.existsBySha256(SHA)).thenThrow(new IllegalStateException("lookup failed"));

        assertThatThrownBy(() -> upload(service(List.of())))
                .hasMessage("insert failed")
                .satisfies(failure -> assertThat(failure.getSuppressed())
                        .extracting(Throwable::getMessage)
                        .contains("lookup failed"));
        verify(storage, never()).delete(BUCKET, "ab/" + SHA);

        when(storage.exists(BUCKET, "ab/" + SHA)).thenReturn(true);
        assertThatThrownBy(() -> upload(service(List.of()))).hasMessage("insert failed");
        verify(files).existsBySha256(SHA);
    }

    @Test
    @DisplayName("A file over the size limit is refused before it is read")
    void oversizedFileIsRefused() {
        assertThatThrownBy(() ->
                        service(List.of()).uploadFile("big.pdf", "application/pdf", content(), 51L * 1024 * 1024, 1L))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.file.size_limit");
        verify(storage, never()).upload(any(), any(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("Uploads over the concurrency limit are refused while the slots are taken")
    void concurrencyLimit() throws Exception {
        CountDownLatch scanning = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FileScanner slow = mock(FileScanner.class);
        when(slow.scan(any(), anyLong(), anyString())).thenAnswer(call -> {
            scanning.countDown();
            release.await(5, TimeUnit.SECONDS);
            return FileScanner.ScanResult.infected("test");
        });
        var service = new MfFileService(
                metadata, storage, new FileContentInspector(), List.of(slow), new MfFileObjectLock(), scopes, 1);
        Thread first = Thread.ofVirtual().start(() -> {
            try {
                upload(service);
            } catch (ApiException expected) {
                // The slow upload ends as infected; only the refusal of the second one matters here.
            }
        });
        try {
            assertThat(scanning.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> upload(service))
                    .isInstanceOf(ApiException.class)
                    .hasFieldOrPropertyWithValue("messageKey", "error.file.uploads_busy");
        } finally {
            release.countDown();
            first.join(5_000);
        }
    }

    @Test
    @DisplayName("A record whose object is gone from the store reads as not found, for a viewer as well")
    void missingObjectIsNotFound() {
        var file = record();
        when(files.findById(eq(file.id()), any())).thenReturn(Optional.of(file));
        when(storage.download(BUCKET, "ab/" + SHA)).thenReturn(null);
        var service = service(List.of());

        assertThatThrownBy(() -> service.downloadFile(file.id()))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.file.object_missing");
        assertThatThrownBy(() -> service.downloadFile(file.id(), 1L))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.file.object_missing");
    }

    private MfFileService service(List<FileScanner> scanners) {
        return new MfFileService(
                metadata, storage, new FileContentInspector(), scanners, new MfFileObjectLock(), scopes);
    }

    private static Object upload(MfFileService service) {
        return service.uploadFile("a.pdf", "application/pdf", content(), CONTENT.length, 1L);
    }

    private static FileScanner scanner(FileScanner.ScanResult result) {
        FileScanner scanner = mock(FileScanner.class);
        when(scanner.getProviderCode()).thenReturn("test");
        when(scanner.scan(any(), anyLong(), anyString())).thenReturn(result);
        return scanner;
    }

    private static FileDownloadStream quarantined() {
        return new FileDownloadStream(content(), CONTENT.length, "application/pdf");
    }

    private static ByteArrayInputStream content() {
        return new ByteArrayInputStream(CONTENT);
    }

    private static MfFileRepository.FileRecord record() {
        return new MfFileRepository.FileRecord(
                UUID.randomUUID(),
                SHA,
                "a.pdf",
                CONTENT.length,
                "application/pdf",
                BUCKET,
                "ab/" + SHA,
                Instant.now(),
                1L);
    }

    private static byte[] pdf(int size) {
        byte[] content = new byte[size];
        byte[] header = "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(header, 0, content, 0, header.length);
        return content;
    }
}
