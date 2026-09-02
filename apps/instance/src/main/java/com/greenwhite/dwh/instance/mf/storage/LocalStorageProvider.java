package com.greenwhite.dwh.instance.mf.storage;

import com.greenwhite.dwh.spi.common.ProviderHealth;
import com.greenwhite.dwh.spi.storage.FileDownloadStream;
import com.greenwhite.dwh.spi.storage.StorageProvider;
import com.greenwhite.dwh.spi.storage.StoredFileMetadata;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

/**
 * File storage provider supporting local filesystem storage with S3 compatible semantics.
 */
@Component
@ConditionalOnProperty(name = "dwh.providers.storage", havingValue = "local_disk", matchIfMissing = true)
public class LocalStorageProvider implements StorageProvider {

    private final Path basePath;

    public LocalStorageProvider(@Value("${dwh.storage.local-path:./data/storage}") String storagePath) {
        this.basePath = Paths.get(storagePath).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.basePath);
        } catch (Exception e) {
            throw new IllegalStateException("Could not initialize local storage path: " + storagePath, e);
        }
    }

    @Override
    public String getProviderCode() {
        return "local_disk";
    }

    @Override
    public StoredFileMetadata upload(String bucket, String key, InputStream contentStream, long sizeBytes, String contentType) {
        if (contentStream == null || sizeBytes < 0) {
            throw new IllegalArgumentException("Storage content and non-negative size are required");
        }
        Path targetFile = resolveObjectPath(bucket, key);
        try {
            if (targetFile.getParent() != null) {
                Files.createDirectories(targetFile.getParent());
            }

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream dis = new DigestInputStream(contentStream, digest);
                 FileOutputStream fos = new FileOutputStream(targetFile.toFile())) {


                byte[] buffer = new byte[8192];
                int bytesRead;
                long written = 0;
                while ((bytesRead = dis.read(buffer)) != -1) {
                    if (written + bytesRead > sizeBytes) {
                        throw new IllegalArgumentException(
                                "Declared storage object size does not match content length");
                    }
                    fos.write(buffer, 0, bytesRead);
                    written += bytesRead;
                }
                if (written != sizeBytes) {
                    throw new IllegalArgumentException(
                            "Declared storage object size does not match content length");
                }
            }

            String sha256 = HexFormat.of().formatHex(digest.digest());
            long actualSize = Files.size(targetFile);

            return new StoredFileMetadata(bucket, key, sha256, actualSize, contentType, Instant.now());
        } catch (IllegalArgumentException e) {
            deletePartialFile(targetFile);
            throw e;
        } catch (Exception e) {
            deletePartialFile(targetFile);
            throw new RuntimeException("Failed to upload file to storage", e);
        }
    }

    @Override
    public FileDownloadStream download(String bucket, String key) {
        Path filePath = resolveObjectPath(bucket, key);
        try {
            if (!Files.exists(filePath)) {
                return null;
            }

            File file = filePath.toFile();
            FileInputStream fis = new FileInputStream(file);
            String probeContentType = Files.probeContentType(filePath);
            String contentType = probeContentType != null ? probeContentType : "application/octet-stream";

            return new FileDownloadStream(fis, file.length(), contentType);
        } catch (Exception e) {
            throw new RuntimeException("Failed to download file from storage", e);
        }
    }

    @Override
    public void delete(String bucket, String key) {
        Path filePath = resolveObjectPath(bucket, key);
        try {
            Files.deleteIfExists(filePath);
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete file from storage", e);
        }
    }

    @Override
    public boolean exists(String bucket, String key) {
        return Files.exists(resolveObjectPath(bucket, key));
    }

    @Override
    public ProviderHealth checkHealth() {
        boolean writable = Files.isWritable(basePath);
        return writable ? ProviderHealth.healthy(getProviderCode(), 1)
                        : ProviderHealth.unhealthy(getProviderCode(), "Storage path is not writable", 1);
    }

    private Path resolveObjectPath(String bucket, String key) {
        if (bucket == null || bucket.isBlank() || key == null || key.isBlank()) {
            throw new IllegalArgumentException("Storage bucket and key are required");
        }
        Path resolved = basePath.resolve(bucket).resolve(key).normalize();
        if (!resolved.startsWith(basePath)) {
            throw new IllegalArgumentException("Unsafe storage bucket or key");
        }
        return resolved;
    }

    private static void deletePartialFile(Path targetFile) {
        try {
            Files.deleteIfExists(targetFile);
        } catch (Exception ignored) {
            // Preserve the original upload error.
        }
    }
}
