package com.greenwhite.dwh.spi.storage;

import com.greenwhite.dwh.spi.common.ProviderHealth;

import java.io.InputStream;

/**
 * Service Provider Interface for File Storage (Garage S3, AWS S3, MinIO, Local Disk).
 */
public interface StorageProvider {

    String getProviderCode();

    StoredFileMetadata upload(String bucket, String key, InputStream contentStream, long sizeBytes, String contentType);

    FileDownloadStream download(String bucket, String key);

    void delete(String bucket, String key);

    boolean exists(String bucket, String key);

    ProviderHealth checkHealth();
}
