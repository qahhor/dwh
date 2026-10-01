package com.smartup24.cms.instance.mf.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.query.QueryCompiler;
import com.smartup24.cms.instance.md.service.MdScopeService;
import com.smartup24.cms.instance.mf.api.FileListItem;
import com.smartup24.cms.instance.mf.api.FileView;
import com.smartup24.cms.instance.mf.api.StorageStats;
import com.smartup24.cms.instance.mf.api.StoredFile;
import com.smartup24.cms.instance.mf.repository.MfFileRepository;
import com.smartup24.cms.spi.storage.FileDownloadStream;
import com.smartup24.cms.spi.storage.FileScanner;
import com.smartup24.cms.spi.storage.StorageProvider;
import com.smartup24.cms.spi.storage.StoredFileMetadata;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class MfFileService {

    private static final String DEFAULT_BUCKET = "instance-files";
    private static final int MAX_FILE_SIZE_MEGABYTES = 50;
    private static final long MAX_FILE_SIZE = MAX_FILE_SIZE_MEGABYTES * 1024L * 1024L;
    private static final Set<String> FORBIDDEN_EXTENSIONS =
            Set.of(".exe", ".sh", ".bat", ".cmd", ".vbs", ".msi", ".jar");

    private final MfFileMetadataService metadataService;
    private final StorageProvider storageProvider;
    private final FileContentInspector contentInspector;
    private final List<FileScanner> fileScanners;
    private final MfFileObjectLock objectLock;
    private final MdScopeService scopeService;
    private final Semaphore uploadLimiter;

    public static final int DEFAULT_MAX_CONCURRENT_UPLOADS = 10;

    @Autowired
    public MfFileService(
            MfFileMetadataService metadataService,
            StorageProvider storageProvider,
            FileContentInspector contentInspector,
            List<FileScanner> fileScanners,
            MfFileObjectLock objectLock,
            MdScopeService scopeService,
            @Value("${dwh.files.max-concurrent-uploads:10}") int maxConcurrentUploads) {
        this.metadataService = metadataService;
        this.storageProvider = storageProvider;
        this.contentInspector = contentInspector;
        this.fileScanners = List.copyOf(fileScanners);
        this.objectLock = objectLock;
        this.scopeService = scopeService;
        int permits = maxConcurrentUploads > 0 ? maxConcurrentUploads : DEFAULT_MAX_CONCURRENT_UPLOADS;
        this.uploadLimiter = new Semaphore(permits);
    }

    public MfFileService(
            MfFileMetadataService metadataService,
            StorageProvider storageProvider,
            FileContentInspector contentInspector,
            List<FileScanner> fileScanners,
            MfFileObjectLock objectLock,
            MdScopeService scopeService) {
        this(
                metadataService,
                storageProvider,
                contentInspector,
                fileScanners,
                objectLock,
                scopeService,
                DEFAULT_MAX_CONCURRENT_UPLOADS);
    }

    /** An upload from the API: the stored file as the client may see it, without the storage fields. */
    public FileView upload(
            String originalName, String mimeType, InputStream contentStream, long sizeBytes, Long createdBy) {
        return view(uploadFile(originalName, mimeType, contentStream, sizeBytes, createdBy));
    }

    /** An upload from another module that keeps a reference to the stored content, its hash included. */
    public StoredFile store(
            String originalName, String mimeType, InputStream contentStream, long sizeBytes, Long createdBy) {
        var file = uploadFile(originalName, mimeType, contentStream, sizeBytes, createdBy);
        return new StoredFile(file.id(), file.originalName(), file.sha256(), file.sizeBytes());
    }

    /** The full ownership record, for this module and its tests. */
    public MfFileRepository.FileRecord uploadFile(
            String originalName, String mimeType, InputStream contentStream, long sizeBytes, Long createdBy) {
        if (!uploadLimiter.tryAcquire()) {
            throw ApiException.rateLimited("error.file.uploads_busy");
        }
        try {
            if (sizeBytes > MAX_FILE_SIZE) {
                throw ApiException.badRequest(
                        ErrorCode.FILE_SIZE_EXCEEDED,
                        "error.file.size_limit",
                        Map.of("megabytes", MAX_FILE_SIZE_MEGABYTES));
            }

            validateFileExtension(originalName);
            FileContentInspector.Inspection inspection = contentInspector.inspect(mimeType, contentStream);
            String verifiedMimeType = inspection.verifiedMimeType();

            metadataService.validateQuotaSnapshot(createdBy, sizeBytes);

            String tempKey = "temp_" + UUID.randomUUID();
            StoredFileMetadata stored =
                    storageProvider.upload(DEFAULT_BUCKET, tempKey, inspection.content(), sizeBytes, verifiedMimeType);
            RuntimeException uploadFailure = null;
            try {
                return publishQuarantinedFile(originalName, createdBy, tempKey, stored, verifiedMimeType);
            } catch (RuntimeException failure) {
                uploadFailure = failure;
                throw failure;
            } finally {
                deleteQuarantinedObject(tempKey, uploadFailure);
            }
        } finally {
            uploadLimiter.release();
        }
    }

    private MfFileRepository.FileRecord publishQuarantinedFile(
            String originalName, Long createdBy, String tempKey, StoredFileMetadata stored, String verifiedMimeType) {
        scanQuarantinedObject(tempKey, stored.sizeBytes(), verifiedMimeType);
        String sha256 = stored.sha256();

        return objectLock.withLock(
                sha256,
                () -> publishQuarantinedFileUnderLock(
                        originalName, createdBy, tempKey, stored, verifiedMimeType, sha256));
    }

    private MfFileRepository.FileRecord publishQuarantinedFileUnderLock(
            String originalName,
            Long createdBy,
            String tempKey,
            StoredFileMetadata stored,
            String verifiedMimeType,
            String sha256) {

        // Свою же копию отдаём как есть: повторная загрузка того же файла не
        // плодит записи и не списывает квоту дважды.
        var ownOpt = metadataService.findBySha256AndOwner(sha256, createdBy);
        if (ownOpt.isPresent()) {
            return ownOpt.get();
        }

        // Дедупликация — на уровне физического объекта, не записи владения (V010).
        // Чужую запись возвращать нельзя: её удаление владельцем каскадом снесло
        // бы вложения у всех остальных, а квота второго загрузившего не росла бы.
        String finalKey = sha256.substring(0, 2) + "/" + sha256;
        var sameContent = metadataService.findBySha256(sha256);
        boolean createdPhysicalObject = false;
        if (sameContent.isPresent()) {
            // Объект уже лежит на диске — переиспользуем его ключ.
            finalKey = sameContent.get().storageKey();
        } else {
            if (!storageProvider.exists(DEFAULT_BUCKET, finalKey)) {
                createdPhysicalObject = true;
                copyQuarantinedObject(tempKey, finalKey, stored.sizeBytes(), verifiedMimeType);
            }
        }

        try {
            // Своя запись владения: своё имя файла, своя квота, своё право на удаление.
            return metadataService.publish(
                    sha256,
                    originalName,
                    stored.sizeBytes(),
                    verifiedMimeType,
                    sameContent.map(MfFileRepository.FileRecord::storageBucket).orElse(DEFAULT_BUCKET),
                    finalKey,
                    createdBy);
        } catch (RuntimeException failure) {
            cleanupUnpublishedObject(sha256, DEFAULT_BUCKET, finalKey, createdPhysicalObject, failure);
            throw failure;
        }
    }

    private void copyQuarantinedObject(String tempKey, String finalKey, long sizeBytes, String contentType) {
        try (FileDownloadStream quarantined = storageProvider.download(DEFAULT_BUCKET, tempKey)) {
            if (quarantined == null || quarantined.inputStream() == null) {
                throw new IllegalStateException("Quarantined object is not readable");
            }
            storageProvider.upload(DEFAULT_BUCKET, finalKey, quarantined.inputStream(), sizeBytes, contentType);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Failed to close quarantined object stream", exception);
        }
    }

    public StorageStats getStorageStats(Long userId) {
        return metadataService.getStorageStats(userId);
    }

    /** The file list through the registry ({@code mf.files}): filter, sort, search {@code q} and the viewer's data scope. */
    public KeysetPage<FileListItem> listFiles(
            Long userId, boolean onlyMine, Integer limit, String cursor, String filter, String sort, String query) {
        var plan = QueryCompiler.compile(MfFileQuery.LIST, filter, sort, limit, cursor, query);
        var page = metadataService.pageFiles(plan, scopeService.filterForFiles(userId), onlyMine ? userId : null);
        return page.map(MfFileService::listItem);
    }

    public void deleteFile(UUID id, Long currentUserId, boolean canDeleteAny) {
        var scope = scopeService.filterForFiles(currentUserId);
        var snapshot = metadataService.requireFile(id, scope);
        objectLock.withLock(snapshot.sha256(), () -> {
            var deletion = metadataService.delete(id, currentUserId, canDeleteAny, scope);
            if (deletion.deletePhysicalObject()) {
                storageProvider.delete(
                        deletion.file().storageBucket(), deletion.file().storageKey());
            }
        });
    }

    public MfFileRepository.FileRecord getFileMetadata(UUID id) {
        return metadataService.requireFile(id);
    }

    /** The file the viewer may see, as the API returns it; a file outside the viewer's scope reads as not found. */
    public FileView getFileMetadata(UUID id, Long currentUserId) {
        return view(requireVisible(id, currentUserId));
    }

    private MfFileRepository.FileRecord requireVisible(UUID id, Long currentUserId) {
        return metadataService.requireFile(id, scopeService.filterForFiles(currentUserId));
    }

    public FileDownloadStream downloadFile(UUID id) {
        var metadata = getFileMetadata(id);
        var stream = storageProvider.download(metadata.storageBucket(), metadata.storageKey());
        if (stream == null) {
            throw ApiException.notFound(ErrorCode.FILE_NOT_FOUND, "error.file.object_missing");
        }
        return stream;
    }

    public FileDownloadStream downloadFile(UUID id, Long currentUserId) {
        var metadata = requireVisible(id, currentUserId);
        var stream = storageProvider.download(metadata.storageBucket(), metadata.storageKey());
        if (stream == null) {
            throw ApiException.notFound(ErrorCode.FILE_NOT_FOUND, "error.file.object_missing");
        }
        return stream;
    }

    private void validateFileExtension(String fileName) {
        if (fileName == null) return;
        String lower = fileName.toLowerCase();
        for (String ext : FORBIDDEN_EXTENSIONS) {
            if (lower.endsWith(ext)) {
                throw ApiException.badRequest(
                        ErrorCode.FILE_TYPE_FORBIDDEN, "error.file.extension_forbidden", Map.of("extension", ext));
            }
        }
    }

    private void scanQuarantinedObject(String tempKey, long sizeBytes, String contentType) {
        for (FileScanner scanner : fileScanners) {
            try (FileDownloadStream quarantined = storageProvider.download(DEFAULT_BUCKET, tempKey)) {
                if (quarantined == null || quarantined.inputStream() == null) {
                    throw new IllegalStateException("Quarantined object is not readable");
                }
                FileScanner.ScanResult result = scanner.scan(quarantined.inputStream(), sizeBytes, contentType);
                if (result == null) {
                    throw new IllegalStateException("File scanner returned no verdict: " + scanner.getProviderCode());
                }
                if (result.verdict() == FileScanner.Verdict.INFECTED) {
                    throw new ApiException(ErrorCode.FILE_MALWARE_DETECTED, "error.file.malware_rejected");
                }
            } catch (ApiException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new ApiException(ErrorCode.FILE_SCAN_FAILED, "error.file.scan_incomplete");
            }
        }
    }

    private void deleteQuarantinedObject(String tempKey, RuntimeException uploadFailure) {
        try {
            storageProvider.delete(DEFAULT_BUCKET, tempKey);
        } catch (RuntimeException cleanupFailure) {
            if (uploadFailure != null) {
                uploadFailure.addSuppressed(cleanupFailure);
            } else {
                throw cleanupFailure;
            }
        }
    }

    private void cleanupUnpublishedObject(
            String sha256,
            String bucket,
            String key,
            boolean createdPhysicalObject,
            RuntimeException publicationFailure) {
        if (!createdPhysicalObject) {
            return;
        }
        try {
            if (!metadataService.existsBySha256(sha256)) {
                storageProvider.delete(bucket, key);
            }
        } catch (RuntimeException cleanupFailure) {
            // Losing metadata is worse than retaining an orphan object. Preserve
            // the publication error and expose cleanup failure as diagnostics.
            publicationFailure.addSuppressed(cleanupFailure);
        }
    }

    private static FileView view(MfFileRepository.FileRecord file) {
        return new FileView(
                file.id(), file.originalName(), file.sizeBytes(), file.mimeType(), file.createdAt(), file.createdBy());
    }

    private static FileListItem listItem(MfFileRepository.FileDetailRecord file) {
        return new FileListItem(
                file.id(),
                file.originalName(),
                file.sizeBytes(),
                file.mimeType(),
                file.createdAt(),
                file.createdBy(),
                file.creatorName(),
                file.creatorLogin());
    }
}
