package com.greenwhite.dwh.instance.mf.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

@ConfigurationProperties(prefix = "dwh.storage.s3")
public class S3StorageProperties {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private URI endpoint;
    private String region = "auto";
    private String accessKey;
    private String secretKey;
    private String bucket;
    private boolean pathStyleAccess = true;
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration readTimeout = Duration.ofSeconds(30);

    public void validate() {
        if (endpoint == null || !endpoint.isAbsolute()
                || !ALLOWED_SCHEMES.contains(endpoint.getScheme().toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException("DWH_S3_ENDPOINT must be an absolute HTTP(S) URI");
        }
        if (endpoint.getUserInfo() != null) {
            throw new IllegalStateException("DWH_S3_ENDPOINT must not contain credentials");
        }
        requireText(region, "DWH_S3_REGION");
        requireText(accessKey, "DWH_S3_ACCESS_KEY");
        requireText(secretKey, "DWH_S3_SECRET_KEY");
        requireText(bucket, "DWH_S3_BUCKET");
        if (bucket.contains("/") || bucket.contains("\\")) {
            throw new IllegalStateException("DWH_S3_BUCKET must be a bucket name, not a path");
        }
        requirePositive(connectTimeout, "DWH_S3_CONNECT_TIMEOUT");
        requirePositive(readTimeout, "DWH_S3_READ_TIMEOUT");
    }

    private static void requireText(String value, String environmentName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(environmentName + " is required when DWH_PROVIDER_STORAGE=s3");
        }
    }

    private static void requirePositive(Duration value, String environmentName) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException(environmentName + " must be greater than zero");
        }
    }

    public URI getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(URI endpoint) {
        this.endpoint = endpoint;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    public boolean isPathStyleAccess() {
        return pathStyleAccess;
    }

    public void setPathStyleAccess(boolean pathStyleAccess) {
        this.pathStyleAccess = pathStyleAccess;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }
}
