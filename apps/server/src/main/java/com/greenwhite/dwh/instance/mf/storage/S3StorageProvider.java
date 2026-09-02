package com.greenwhite.dwh.instance.mf.storage;

import com.greenwhite.dwh.spi.common.ProviderHealth;
import com.greenwhite.dwh.spi.storage.FileDownloadStream;
import com.greenwhite.dwh.spi.storage.StorageProvider;
import com.greenwhite.dwh.spi.storage.StoredFileMetadata;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

public final class S3StorageProvider implements StorageProvider {

    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private final S3Client client;
    private final S3StorageProperties properties;

    public S3StorageProvider(S3Client client, S3StorageProperties properties) {
        properties.validate();
        this.client = client;
        this.properties = properties;
    }

    @Override
    public String getProviderCode() {
        return "s3";
    }

    @Override
    public StoredFileMetadata upload(
            String bucket,
            String key,
            InputStream contentStream,
            long sizeBytes,
            String contentType) {
        validateObjectAddress(bucket, key);
        if (contentStream == null || sizeBytes < 0) {
            throw new IllegalArgumentException("Storage content and non-negative size are required");
        }

        Path staged = null;
        try {
            staged = Files.createTempFile("smartupcms-s3-", ".upload");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream source = new DigestInputStream(contentStream, digest);
                 OutputStream target = Files.newOutputStream(staged)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                long written = 0;
                while ((bytesRead = source.read(buffer)) != -1) {
                    if (written + bytesRead > sizeBytes) {
                        throw new IllegalArgumentException(
                                "Declared storage object size does not match content length");
                    }
                    target.write(buffer, 0, bytesRead);
                    written += bytesRead;
                }
                if (written != sizeBytes) {
                    throw new IllegalArgumentException(
                            "Declared storage object size does not match content length");
                }
            }

            long actualSize = Files.size(staged);

            byte[] hash = digest.digest();
            String sha256 = HexFormat.of().formatHex(hash);
            String checksum = Base64.getEncoder().encodeToString(hash);
            String resolvedContentType = normalizedContentType(contentType);
            var request = PutObjectRequest.builder()
                    .bucket(properties.getBucket())
                    .key(physicalKey(bucket, key))
                    .contentLength(actualSize)
                    .contentType(resolvedContentType)
                    .checksumSHA256(checksum)
                    .metadata(Map.of("smartupcms-sha256", sha256))
                    .build();

            var response = client.putObject(request, RequestBody.fromFile(staged));
            if (response.checksumSHA256() != null && !checksum.equals(response.checksumSHA256())) {
                delete(bucket, key);
                throw new IllegalStateException("S3 response checksum does not match uploaded content");
            }
            return new StoredFileMetadata(
                    bucket, key, sha256, actualSize, resolvedContentType, Instant.now());
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw storageFailure("upload", error);
        } finally {
            deleteStagedFile(staged);
        }
    }

    @Override
    public FileDownloadStream download(String bucket, String key) {
        validateObjectAddress(bucket, key);
        try {
            ResponseInputStream<GetObjectResponse> stream = client.getObject(GetObjectRequest.builder()
                    .bucket(properties.getBucket())
                    .key(physicalKey(bucket, key))
                    .checksumMode("ENABLED")
                    .build());
            GetObjectResponse response = stream.response();
            return new FileDownloadStream(
                    stream,
                    response.contentLength() == null ? 0 : response.contentLength(),
                    normalizedContentType(response.contentType()));
        } catch (NoSuchKeyException error) {
            return null;
        } catch (S3Exception error) {
            if (error.statusCode() == 404) {
                return null;
            }
            throw storageFailure("download", error);
        } catch (Exception error) {
            throw storageFailure("download", error);
        }
    }

    @Override
    public void delete(String bucket, String key) {
        validateObjectAddress(bucket, key);
        try {
            client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(properties.getBucket())
                    .key(physicalKey(bucket, key))
                    .build());
        } catch (Exception error) {
            throw storageFailure("delete", error);
        }
    }

    @Override
    public boolean exists(String bucket, String key) {
        validateObjectAddress(bucket, key);
        try {
            client.headObject(HeadObjectRequest.builder()
                    .bucket(properties.getBucket())
                    .key(physicalKey(bucket, key))
                    .build());
            return true;
        } catch (NoSuchKeyException error) {
            return false;
        } catch (S3Exception error) {
            if (error.statusCode() == 404) {
                return false;
            }
            throw storageFailure("exists", error);
        } catch (Exception error) {
            throw storageFailure("exists", error);
        }
    }

    @Override
    public ProviderHealth checkHealth() {
        Instant startedAt = Instant.now();
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(properties.getBucket()).build());
            return ProviderHealth.healthy(getProviderCode(), elapsedMillis(startedAt));
        } catch (Exception error) {
            return ProviderHealth.unhealthy(
                    getProviderCode(), "S3 storage is unavailable", elapsedMillis(startedAt));
        }
    }

    private static RuntimeException storageFailure(String operation, Exception cause) {
        return new RuntimeException("S3 storage " + operation + " failed", cause);
    }

    private static long elapsedMillis(Instant startedAt) {
        return Math.max(0, Duration.between(startedAt, Instant.now()).toMillis());
    }

    private static String normalizedContentType(String contentType) {
        return contentType == null || contentType.isBlank() ? DEFAULT_CONTENT_TYPE : contentType;
    }

    private static void validateObjectAddress(String bucket, String key) {
        if (bucket == null || bucket.isBlank() || key == null || key.isBlank()) {
            throw new IllegalArgumentException("Storage bucket and key are required");
        }
        if (bucket.contains("/") || bucket.contains("\\")
                || key.startsWith("/") || key.contains("\\") || containsParentSegment(key)
                || key.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Unsafe storage bucket or key");
        }
        if (physicalKey(bucket, key).getBytes(StandardCharsets.UTF_8).length > 1024) {
            throw new IllegalArgumentException("Storage key is too long");
        }
    }

    private static boolean containsParentSegment(String key) {
        for (String segment : key.split("/", -1)) {
            if (segment.equals("..")) {
                return true;
            }
        }
        return false;
    }

    private static String physicalKey(String bucket, String key) {
        return bucket + "/" + key;
    }

    private static void deleteStagedFile(Path staged) {
        if (staged == null) {
            return;
        }
        try {
            Files.deleteIfExists(staged);
        } catch (IOException ignored) {
            // The OS will reclaim the temp directory; never mask the storage result.
        }
    }
}
