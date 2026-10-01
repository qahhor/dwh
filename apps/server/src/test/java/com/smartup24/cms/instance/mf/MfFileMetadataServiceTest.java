package com.smartup24.cms.instance.mf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.md.service.MdStorageQuotaService;
import com.smartup24.cms.instance.mf.repository.MfFileRepository;
import com.smartup24.cms.instance.mf.service.MfFileMetadataService;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

class MfFileMetadataServiceTest {

    @Test
    void returnsConcurrentOwnerWinnerBeforeChargingQuotaAgain() {
        MfFileRepository repository = Mockito.mock(MfFileRepository.class);
        MfFileMetadataService service = new MfFileMetadataService(
                repository, Mockito.mock(AuditLogService.class), Mockito.mock(MdStorageQuotaService.class));
        MfFileRepository.FileRecord winner = record();

        when(repository.findBySha256AndOwner(winner.sha256(), 7L)).thenReturn(Optional.empty(), Optional.of(winner));

        var result = service.publish(
                winner.sha256(),
                winner.originalName(),
                winner.sizeBytes(),
                winner.mimeType(),
                winner.storageBucket(),
                winner.storageKey(),
                7L);

        assertThat(result).isEqualTo(winner);
        Mockito.verify(repository).lockQuotaBudget();
        Mockito.verify(repository, Mockito.never()).getTotalCompanyUsedBytes();
        Mockito.verify(repository, Mockito.never())
                .create(anyString(), anyString(), anyLong(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void locksQuotaBudgetBeforeFinalUsageCheckAndInsert() {
        MfFileRepository repository = Mockito.mock(MfFileRepository.class);
        AuditLogService auditLog = Mockito.mock(AuditLogService.class);
        MdStorageQuotaService quotas = Mockito.mock(MdStorageQuotaService.class);
        MfFileMetadataService service = new MfFileMetadataService(repository, auditLog, quotas);
        MfFileRepository.FileRecord created = record();

        when(repository.findBySha256AndOwner(created.sha256(), 7L)).thenReturn(Optional.empty());
        when(quotas.instanceQuotaBytes()).thenReturn(10_000L);
        when(repository.getTotalCompanyUsedBytes()).thenReturn(1_000L);
        when(quotas.userQuotaBytes(7L)).thenReturn(5_000L);
        when(repository.getUserUsedBytes(7L)).thenReturn(500L);
        when(repository.findBySha256(created.sha256())).thenReturn(Optional.empty());
        when(repository.create(anyString(), anyString(), anyLong(), anyString(), anyString(), anyString(), any()))
                .thenReturn(created);

        var result = service.publish(
                created.sha256(),
                created.originalName(),
                created.sizeBytes(),
                created.mimeType(),
                created.storageBucket(),
                created.storageKey(),
                7L);

        assertThat(result).isEqualTo(created);
        InOrder order = inOrder(repository, quotas);
        order.verify(repository).lockQuotaBudget();
        order.verify(quotas).instanceQuotaBytes();
        order.verify(repository).getTotalCompanyUsedBytes();
        order.verify(quotas).userQuotaBytes(7L);
        order.verify(repository).getUserUsedBytes(7L);
        order.verify(repository)
                .create(
                        created.sha256(),
                        created.originalName(),
                        created.sizeBytes(),
                        created.mimeType(),
                        created.storageBucket(),
                        created.storageKey(),
                        7L);
    }

    private static MfFileRepository.FileRecord record() {
        String sha = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        return new MfFileRepository.FileRecord(
                UUID.randomUUID(),
                sha,
                "report.pdf",
                1_024,
                "application/pdf",
                "instance-files",
                "e3/" + sha,
                Instant.now(),
                7L);
    }
}
