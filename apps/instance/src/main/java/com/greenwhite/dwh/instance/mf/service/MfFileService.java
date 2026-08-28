package com.greenwhite.dwh.instance.mf.service;

import com.greenwhite.dwh.core.error.ErrorCode;
import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.mf.repository.MfFileRepository;
import com.greenwhite.dwh.spi.storage.FileDownloadStream;
import com.greenwhite.dwh.spi.storage.StorageProvider;
import com.greenwhite.dwh.spi.storage.StoredFileMetadata;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.Set;
import java.util.UUID;

@Service
public class MfFileService {

    private static final String DEFAULT_BUCKET = "instance-files";
    private static final long MAX_FILE_SIZE = 50 * 1024 * 1024; // 50MB
    private static final Set<String> FORBIDDEN_EXTENSIONS = Set.of(".exe", ".sh", ".bat", ".cmd", ".vbs", ".msi", ".jar");

    private final MfFileRepository fileRepository;
    private final StorageProvider storageProvider;

    public MfFileService(MfFileRepository fileRepository, StorageProvider storageProvider) {
        this.fileRepository = fileRepository;
        this.storageProvider = storageProvider;
    }

    @Transactional
    public MfFileRepository.FileRecord uploadFile(String originalName, String mimeType, InputStream contentStream, long sizeBytes, Long createdBy) {
        if (sizeBytes > MAX_FILE_SIZE) {
            throw ApiException.badRequest(ErrorCode.FILE_SIZE_EXCEEDED, "Размер файла превышает лимит 50 МБ");
        }

        validateFileExtension(originalName);

        String tempKey = "temp_" + UUID.randomUUID();
        StoredFileMetadata stored = storageProvider.upload(DEFAULT_BUCKET, tempKey, contentStream, sizeBytes, mimeType);

        // Deduplication Check
        var existingOpt = fileRepository.findBySha256(stored.sha256());
        if (existingOpt.isPresent()) {
            // Delete duplicate temp file, reuse existing storage
            storageProvider.delete(DEFAULT_BUCKET, tempKey);
            return existingOpt.get();
        }

        // Final storage key using SHA-256
        String finalKey = stored.sha256().substring(0, 2) + "/" + stored.sha256();
        if (!storageProvider.exists(DEFAULT_BUCKET, finalKey)) {
            storageProvider.upload(DEFAULT_BUCKET, finalKey, storageProvider.download(DEFAULT_BUCKET, tempKey).inputStream(), stored.sizeBytes(), mimeType);
        }
        storageProvider.delete(DEFAULT_BUCKET, tempKey);

        return fileRepository.create(
                stored.sha256(),
                originalName,
                stored.sizeBytes(),
                mimeType != null ? mimeType : "application/octet-stream",
                DEFAULT_BUCKET,
                finalKey,
                createdBy
        );
    }

    @Transactional(readOnly = true)
    public MfFileRepository.FileRecord getFileMetadata(UUID id) {
        return fileRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.FILE_NOT_FOUND, "Файл не найден"));
    }

    @Transactional(readOnly = true)
    public FileDownloadStream downloadFile(UUID id) {
        var metadata = getFileMetadata(id);
        var stream = storageProvider.download(metadata.storageBucket(), metadata.storageKey());
        if (stream == null) {
            throw ApiException.notFound(ErrorCode.FILE_NOT_FOUND, "Физический файл не найден в хранилище");
        }
        return stream;
    }

    private void validateFileExtension(String fileName) {
        if (fileName == null) return;
        String lower = fileName.toLowerCase();
        for (String ext : FORBIDDEN_EXTENSIONS) {
            if (lower.endsWith(ext)) {
                throw ApiException.badRequest(ErrorCode.FILE_TYPE_FORBIDDEN, "Загрузка исполняемых файлов (" + ext + ") запрещена правилами безопасности");
            }
        }
    }
}
