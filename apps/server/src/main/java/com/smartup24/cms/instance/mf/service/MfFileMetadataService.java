package com.smartup24.cms.instance.mf.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.query.QueryPlan;
import com.smartup24.cms.instance.common.security.ScopeFilter;
import com.smartup24.cms.instance.md.service.MdStorageQuotaService;
import com.smartup24.cms.instance.mf.api.StorageStats;
import com.smartup24.cms.instance.mf.repository.MfFileRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final MdStorageQuotaService quotas;

    public MfFileMetadataService(
            MfFileRepository fileRepository, AuditLogService auditLogService, MdStorageQuotaService quotas) {
        this.fileRepository = fileRepository;
        this.auditLogService = auditLogService;
        this.quotas = quotas;
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
        String canonicalBucket =
                sharedObject.map(MfFileRepository.FileRecord::storageBucket).orElse(storageBucket);
        String canonicalKey =
                sharedObject.map(MfFileRepository.FileRecord::storageKey).orElse(storageKey);

        try {
            var created = fileRepository.create(
                    sha256, originalName, sizeBytes, mimeType, canonicalBucket, canonicalKey, ownerId);

            auditLogService.logChange(
                    "mf_files",
                    created.id().toString(),
                    "I",
                    List.of("original_name", "size_bytes", "mime_type"),
                    null,
                    Map.of("original_name", originalName, "size_bytes", sizeBytes, "mime_type", created.mimeType()));
            return created;
        } catch (DuplicateKeyException exception) {
            return fileRepository.findBySha256AndOwner(sha256, ownerId).orElseThrow(() -> exception);
        }
    }

    @Transactional
    public DeletionResult delete(UUID id, Long currentUserId, boolean canDeleteAny) {
        return delete(id, currentUserId, canDeleteAny, ScopeFilter.unrestricted());
    }

    @Transactional
    public DeletionResult delete(UUID id, Long currentUserId, boolean canDeleteAny, ScopeFilter scope) {
        var file = requireFile(id, scope);
        if (!canDeleteAny && (file.createdBy() == null || !file.createdBy().equals(currentUserId))) {
            throw ApiException.forbidden("error.file.delete_forbidden");
        }

        try {
            fileRepository.delete(id);
        } catch (DataIntegrityViolationException attached) {
            // A file of an entity's file or image field (mf_record_files, on delete restrict; ADR-0032, 4.7).
            ApiException refused = ApiException.conflict(ErrorCode.CONFLICT, "error.file.attached_to_record");
            refused.initCause(attached);
            throw refused;
        }
        auditLogService.logChange(
                "mf_files",
                id.toString(),
                "D",
                List.of("original_name", "size_bytes", "created_by"),
                Map.of(
                        "original_name",
                        file.originalName(),
                        "size_bytes",
                        file.sizeBytes(),
                        "created_by",
                        file.createdBy() != null ? file.createdBy() : "null"),
                null);

        return new DeletionResult(file, !fileRepository.existsBySha256(file.sha256()));
    }

    @Transactional(readOnly = true)
    public MfFileRepository.FileRecord requireFile(UUID id) {
        return requireFile(id, ScopeFilter.unrestricted());
    }

    @Transactional(readOnly = true)
    public MfFileRepository.FileRecord requireFile(UUID id, ScopeFilter scope) {
        return fileRepository.findById(id, scope).orElseThrow(() -> new ApiException(ErrorCode.FILE_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public StorageStats getStorageStats(Long userId) {
        long companyQuota = quotas.instanceQuotaBytes();
        long companyUsed = fileRepository.getTotalCompanyUsedBytes();
        long userQuota = quotas.userQuotaBytes(userId);
        long userUsed = fileRepository.getUserUsedBytes(userId);

        return new StorageStats(
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
    public KeysetPage<MfFileRepository.FileDetailRecord> pageFiles(
            QueryPlan plan, ScopeFilter scope, Long onlyOwnerId) {
        return fileRepository.pageFiles(plan, scope, onlyOwnerId);
    }

    private void validateQuota(Long ownerId, long requestedBytes) {
        long companyQuota = quotas.instanceQuotaBytes();
        long companyUsed = fileRepository.getTotalCompanyUsedBytes();
        if (exceedsQuota(companyUsed, requestedBytes, companyQuota)) {
            throw ApiException.badRequest(
                    ErrorCode.STORAGE_QUOTA_EXCEEDED,
                    "error.file.company_quota_exceeded",
                    Map.of("quota", formatBytes(companyQuota), "used", formatBytes(companyUsed)));
        }

        if (ownerId != null) {
            long userQuota = quotas.userQuotaBytes(ownerId);
            long userUsed = fileRepository.getUserUsedBytes(ownerId);
            if (exceedsQuota(userUsed, requestedBytes, userQuota)) {
                throw ApiException.badRequest(
                        ErrorCode.USER_STORAGE_QUOTA_EXCEEDED,
                        "error.file.user_quota_exceeded",
                        Map.of("quota", formatBytes(userQuota), "used", formatBytes(userUsed)));
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

    public record DeletionResult(MfFileRepository.FileRecord file, boolean deletePhysicalObject) {}
}
