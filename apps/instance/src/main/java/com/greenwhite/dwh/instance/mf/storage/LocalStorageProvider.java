package com.greenwhite.dwh.instance.mf.storage;

import com.greenwhite.dwh.spi.common.ProviderHealth;
import com.greenwhite.dwh.spi.storage.FileDownloadStream;
import com.greenwhite.dwh.spi.storage.StorageProvider;
import com.greenwhite.dwh.spi.storage.StoredFileMetadata;
import org.springframework.beans.factory.annotation.Value;
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
        try {
            Path targetDir = basePath.resolve(bucket).normalize();
            Files.createDirectories(targetDir);

            Path targetFile = targetDir.resolve(key).normalize();
            if (!targetFile.startsWith(basePath)) {
                throw new SecurityException("Path traversal attempt in storage key: " + key);
            }

            if (targetFile.getParent() != null) {
                Files.createDirectories(targetFile.getParent());
            }

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream dis = new DigestInputStream(contentStream, digest);
                 FileOutputStream fos = new FileOutputStream(targetFile.toFile())) {


                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = dis.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                }
            }

            String sha256 = HexFormat.of().formatHex(digest.digest());
            long actualSize = Files.size(targetFile);

            return new StoredFileMetadata(bucket, key, sha256, actualSize, contentType, Instant.now());
        } catch (Exception e) {
            throw new RuntimeException("Failed to upload file to storage", e);
        }
    }

    @Override
    public FileDownloadStream download(String bucket, String key) {
        try {
            Path filePath = basePath.resolve(bucket).resolve(key).normalize();
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
        try {
            Path filePath = basePath.resolve(bucket).resolve(key).normalize();
            Files.deleteIfExists(filePath);
        } catch (Exception ignored) {}
    }

    @Override
    public boolean exists(String bucket, String key) {
        Path filePath = basePath.resolve(bucket).resolve(key).normalize();
        return Files.exists(filePath);
    }

    @Override
    public ProviderHealth checkHealth() {
        boolean writable = Files.isWritable(basePath);
        return writable ? ProviderHealth.healthy(getProviderCode(), 1)
                        : ProviderHealth.unhealthy(getProviderCode(), "Storage path is not writable", 1);
    }
}
