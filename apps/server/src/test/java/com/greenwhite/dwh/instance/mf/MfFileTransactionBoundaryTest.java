package com.greenwhite.dwh.instance.mf;

import com.greenwhite.dwh.instance.audit.service.AuditLogService;
import com.greenwhite.dwh.instance.mf.repository.MfFileRepository;
import com.greenwhite.dwh.instance.mf.service.FileContentInspector;
import com.greenwhite.dwh.instance.mf.service.MfFileService;
import com.greenwhite.dwh.instance.mf.service.MfFileMetadataService;
import com.greenwhite.dwh.instance.mf.service.MfFileObjectLock;
import com.greenwhite.dwh.spi.storage.StorageProvider;
import com.greenwhite.dwh.spi.storage.FileDownloadStream;
import com.greenwhite.dwh.spi.storage.StoredFileMetadata;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class MfFileTransactionBoundaryTest {

    private static final String SHA = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Test
    void storageIoDoesNotRunInsideDatabaseTransaction() throws Exception {
        MfFileRepository repository = Mockito.mock(MfFileRepository.class);
        StorageProvider storage = Mockito.mock(StorageProvider.class);
        AuditLogService auditLog = Mockito.mock(AuditLogService.class);
        List<Boolean> transactionStatesAtStorageBoundary = new ArrayList<>();
        byte[] content = pdfBytes(128);

        when(repository.getCompanyQuotaBytes()).thenReturn(10_000_000L);
        when(repository.getTotalCompanyUsedBytes()).thenReturn(0L);
        when(repository.getUserEffectiveQuotaBytes(1L)).thenReturn(1_000_000L);
        when(repository.getUserUsedBytes(1L)).thenReturn(0L);
        when(repository.findBySha256AndOwner(SHA, 1L)).thenReturn(Optional.of(record()));
        when(repository.findById(any())).thenReturn(Optional.of(record()));
        when(repository.existsBySha256(SHA)).thenReturn(false);
        when(storage.upload(anyString(), anyString(), any(), anyLong(), anyString()))
                .thenAnswer(invocation -> {
                    transactionStatesAtStorageBoundary.add(
                            TransactionSynchronizationManager.isActualTransactionActive());
                    return new StoredFileMetadata(
                            "instance-files", invocation.getArgument(1), SHA, content.length,
                            "application/pdf", Instant.now());
                });
        when(storage.download(anyString(), anyString()))
                .thenAnswer(invocation -> {
                    transactionStatesAtStorageBoundary.add(
                            TransactionSynchronizationManager.isActualTransactionActive());
                    return new FileDownloadStream(
                            new ByteArrayInputStream(content), content.length, "application/pdf");
                });
        Mockito.doAnswer(invocation -> {
                    transactionStatesAtStorageBoundary.add(
                            TransactionSynchronizationManager.isActualTransactionActive());
                    return null;
                })
                .when(storage).delete(anyString(), anyString());

        try (var context = new AnnotationConfigApplicationContext()) {
            DataSource dataSource = new DriverManagerDataSource(
                    "jdbc:h2:mem:file_tx_boundary;DB_CLOSE_DELAY=-1", "sa", "");
            context.register(EnableTransactions.class);
            context.registerBean(DataSource.class, () -> dataSource);
            context.registerBean(PlatformTransactionManager.class,
                    () -> new DataSourceTransactionManager(dataSource));
            context.registerBean(MfFileMetadataService.class,
                    () -> new MfFileMetadataService(repository, auditLog));
            context.registerBean(MfFileService.class,
                    () -> new MfFileService(
                            context.getBean(MfFileMetadataService.class),
                            storage, new FileContentInspector(), List.of(),
                            new MfFileObjectLock()));
            context.refresh();

            MfFileService service = context.getBean(MfFileService.class);
            service.uploadFile(
                    "document.pdf", "application/pdf",
                    new ByteArrayInputStream(content), content.length, 1L);
            try (var ignored = service.downloadFile(record().id())) {
                // Closing verifies the same real stream contract used by the controller.
            }
            service.deleteFile(record().id(), 1L, false);
        }

        assertThat(transactionStatesAtStorageBoundary)
                .as("database connections must not be held while storage I/O is in progress")
                .isNotEmpty()
                .containsOnly(false);
    }

    private static MfFileRepository.FileRecord record() {
        return new MfFileRepository.FileRecord(
                UUID.randomUUID(), SHA, "document.pdf", 128, "application/pdf",
                "instance-files", "e3/" + SHA, Instant.now(), 1L);
    }

    private static byte[] pdfBytes(int size) {
        byte[] content = new byte[size];
        byte[] header = "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(header, 0, content, 0, Math.min(header.length, content.length));
        return content;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class EnableTransactions {
    }
}
