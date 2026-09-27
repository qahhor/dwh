package com.smartup24.cms.instance.audit.archive;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Archives in an S3 bucket of their own ({@code smc.audit.archive.s3.*}), under a key prefix. The client is private
 * to the store: a second {@link S3Client} bean would clash with the one of the file storage.
 */
public class S3AuditArchiveStore implements AuditArchiveStore, AutoCloseable {

    private final S3Client client;
    private final String bucket;
    private final String prefix;

    public S3AuditArchiveStore(S3Client client, String bucket, String prefix) {
        this.client = client;
        this.bucket = bucket;
        String normalized = prefix == null ? "" : prefix.replaceAll("^/+", "");
        this.prefix = normalized.isEmpty() || normalized.endsWith("/") ? normalized : normalized + "/";
    }

    public static S3AuditArchiveStore from(AuditArchiveProperties.S3 s3) {
        s3.validate();
        S3Client client = S3Client.builder()
                .endpointOverride(s3.endpoint())
                .region(Region.of(s3.region()))
                .credentialsProvider(
                        StaticCredentialsProvider.create(AwsBasicCredentials.create(s3.accessKey(), s3.secretKey())))
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(10))
                        .socketTimeout(Duration.ofMinutes(5)))
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_SUPPORTED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_SUPPORTED)
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(s3.pathStyleAccess())
                        .build())
                .build();
        return new S3AuditArchiveStore(client, s3.bucket(), s3.prefix());
    }

    @Override
    public String storage() {
        return AuditArchiveProperties.S3_TARGET;
    }

    @Override
    public void put(String key, Path file) {
        client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(prefix + key)
                        .contentType("application/gzip")
                        .build(),
                RequestBody.fromFile(file));
    }

    @Override
    public InputStream open(String key) {
        return client.getObject(
                GetObjectRequest.builder().bucket(bucket).key(prefix + key).build());
    }

    @Override
    public boolean exists(String key) {
        try {
            client.headObject(
                    HeadObjectRequest.builder().bucket(bucket).key(prefix + key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public void delete(String key) {
        client.deleteObject(
                DeleteObjectRequest.builder().bucket(bucket).key(prefix + key).build());
    }

    @Override
    public void close() {
        client.close();
    }
}
