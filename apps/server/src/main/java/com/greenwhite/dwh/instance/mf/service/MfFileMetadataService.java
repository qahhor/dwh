package com.greenwhite.dwh.instance.mf.service;

import com.greenwhite.dwh.core.error.ErrorCode;
import com.greenwhite.dwh.instance.audit.service.AuditLogService;
import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.mf.repository.MfFileRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns the short database transactions used by file workflows.
 *
 * <p>This component deliberately has no storage or malware-scanner dependency:
 * remote I/O must complete before or after these transaction boundaries so a
 * slow object store cannot retain a JDBC connection.</p>
 */
@Service
public class MfFileMetadataService {

    private final MfFileRepository fileRepository;
    private final AuditLogService auditLogService;

    public MfFileMetadataService(MfFileRepository fileRepository, AuditLogService auditLogService) {
        this.fileRepository = fileRepository;
        this.auditLogService = auditLogService;
    }

    /** Fast, advisory check before spending object-storage and scanner capacity. */
    @Transactional(readOnly = true)
    public void validateQuotaSnapshot(Long ownerId, long requestedBytes) {
        validateQuota(ownerId, requestedBytes);
    }

    @Transactional(readOnly = true)
    public Optional<MfFileRepository.FileRecord> findBySha256AndOwner(String sha256, Long ownerId) {
        return fileRepository.findBySha256AndOwner(sha256, ownerId);
    }

    @Transactional(readOnly = true)
    public Optional<MfFileRepository.FileRecord> findBySha256(String sha256) {
        return fileRepository.findBySha256(sha256);
    }

    @Transactional(readOnly = true)
    public boolean existsBySha256(String sha256) {
        return fileRepository.existsBySha256(sha256);
    }

    /**
     * Atomically rechecks quota and publishes one ownership row.
     * The transaction-scoped PostgreSQL advisory lock serializes quota writers;
     * the unique owner/content index remains the last line of idempotency defence.
     */
    @Transactional
    public MfFileRepository.FileRecord publish(
            String sha256,
            String originalName,
            long sizeBytes,
            String mimeType,
            String storageBucket,
            String storageKey,
            Long ownerId) {
        var own = fileRepository.findBySha256AndOwner(sha256, ownerId);
        if (own.isPresent()) {
            return own.get();
        }

        fileRepository.lockQuotaBudget();
        // The winner may have committed while this request was waiting for the
        // quota lock. Returning it is idempotent and must not charge quota twice.
        own = fileRepository.findBySha256AndOwner(sha256, ownerId);
        if (own.isPresent()) {
            return own.get();
        }
        validateQuota(ownerId, sizeBytes);

        // A second process may have published this content after the storage
        // preflight. Reuse its canonical location when that happened.
        var sharedObject = fileRepository.findBySha256(sha256);
        String canonicalBucket = sharedObject
                .map(MfFileRepository.FileRecord::storageBucket)
                .orElse(storageBucket);
        String canonicalKey = sharedObject
                .map(MfFileRepository.FileRecord::storageKey)
                .orElse(storageKey);

        try {
            var created = fileRepository.create(
                    sha256,
                    originalName,
                    sizeBytes,
                    mimeType,
                    canonicalBucket,
                    canonicalKey,
                    ownerId);

            auditLogService.logChange("mf_files", created.id().toString(), "I",
                    List.of("original_name", "size_bytes", "mime_type"),
                    null,
                    Map.of("original_name", originalName,
                            "size_bytes", sizeBytes,
                            "mime_type", created.mimeType()));
            return created;
        } catch (DuplicateKeyException exception) {
            return fileRepository.findBySha256AndOwner(sha256, ownerId)
                    .orElseThrow(() -> exception);
        }
    }

    @Transactional
    public DeletionResult delete(UUID id, Long currentUserId, boolean canDeleteAny) {
        var file = requireFile(id);
        if (!canDeleteAny && (file.createdBy() == null || !file.createdBy().equals(currentUserId))) {
            throw ApiException.forbidden("У вас нет прав на удаление этого файла");
        }

        fileRepository.delete(id);
        auditLogService.logChange("mf_files", id.toString(), "D",
                List.of("original_name", "size_bytes", "created_by"),
                Map.of("original_name", file.originalName(),
                        "size_bytes", file.sizeBytes(),
                        "created_by", file.createdBy() != null ? file.createdBy() : "null"),
                null);

        return new DeletionResult(file, !fileRepository.existsBySha256(file.sha256()));
    }

    @Transactional(readOnly = true)
    public MfFileRepository.FileRecord requireFile(UUID id) {
        return fileRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.FILE_NOT_FOUND, "Файл не найден"));
    }

    @Transactional(readOnly = true)
    public MfFileService.StorageStats getStorageStats(Long userId) {
        long companyQuota = fileRepository.getCompanyQuotaBytes();
        long companyUsed = fileRepository.getTotalCompanyUsedBytes();
        long userQuota = fileRepository.getUserEffectiveQuotaBytes(userId);
        long userUsed = fileRepository.getUserUsedBytes(userId);

        return new MfFileService.StorageStats(
                companyQuota,
                companyUsed,
                Math.max(0, companyQuota - companyUsed),
                userQuota,
                userUsed,
                Math.max(0, userQuota - userUsed),
                fileRepository.countTotalFiles(),
                fileRepository.countUserFiles(userId));
    }

    @Transactional(readOnly = true)
    public List<MfFileRepository.FileDetailRecord> listFiles(
            Long userId, boolean onlyMine, String query, int limit) {
        return fileRepository.listFiles(userId, onlyMine, query, limit);
    }

    private void validateQuota(Long ownerId, long requestedBytes) {
        long companyQuota = fileRepository.getCompanyQuotaBytes();
        long companyUsed = fileRepository.getTotalCompanyUsedBytes();
        if (exceedsQuota(companyUsed, requestedBytes, companyQuota)) {
            throw ApiException.badRequest(ErrorCode.STORAGE_QUOTA_EXCEEDED,
                    "Превышена дисковая квота компании (" + formatBytes(companyQuota)
                            + "). Занято: " + formatBytes(companyUsed));
        }

        if (ownerId != null) {
            long userQuota = fileRepository.getUserEffectiveQuotaBytes(ownerId);
            long userUsed = fileRepository.getUserUsedBytes(ownerId);
            if (exceedsQuota(userUsed, requestedBytes, userQuota)) {
                throw ApiException.badRequest(ErrorCode.USER_STORAGE_QUOTA_EXCEEDED,
                        "Превышена ваша персональная дисковая квота (" + formatBytes(userQuota)
                                + "). Занято: " + formatBytes(userUsed));
            }
        }
    }

    private boolean exceedsQuota(long usedBytes, long requestedBytes, long quotaBytes) {
        return requestedBytes > quotaBytes || usedBytes > quotaBytes - requestedBytes;
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String prefix = "KMGTPE".charAt(exp - 1) + "";
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), prefix);
    }

    public record DeletionResult(MfFileRepository.FileRecord file, boolean deletePhysicalObject) {
    }
}
