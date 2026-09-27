package com.smartup24.cms.instance.audit.archive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;

/**
 * Archiving of the audit log (decision of 2026-09-27): closed partitions go into one gzip file every
 * {@link #interval()} or as soon as they reach {@link #sizeThreshold()}; files are kept {@link #retention()};
 * a partition leaves the database only when {@link #deleteAfterArchive()} is on.
 *
 * @param target      {@code local} (a directory on the server) or {@code s3}
 * @param localPath   the directory of a local target, and the staging area of both targets
 */
@ConfigurationProperties(prefix = "smc.audit.archive")
public record AuditArchiveProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("local") String target,
        @DefaultValue("/var/lib/smartupcms/audit-archive") Path localPath,
        @DefaultValue("7d") Duration interval,
        @DefaultValue("100MB") DataSize sizeThreshold,
        @DefaultValue("90d") Duration retention,
        @DefaultValue("false") boolean deleteAfterArchive,
        @DefaultValue S3 s3
) {

    public static final String LOCAL = "local";
    public static final String S3_TARGET = "s3";

    public AuditArchiveProperties {
        target = target == null ? LOCAL : target.trim().toLowerCase(Locale.ROOT);
        if (!LOCAL.equals(target) && !S3_TARGET.equals(target)) {
            throw new IllegalStateException("SMC_AUDIT_ARCHIVE_TARGET must be local or s3, got " + target);
        }
        requirePositive(interval, "SMC_AUDIT_ARCHIVE_INTERVAL");
        requirePositive(retention, "SMC_AUDIT_ARCHIVE_RETENTION");
        if (sizeThreshold == null || sizeThreshold.toBytes() <= 0) {
            throw new IllegalStateException("SMC_AUDIT_ARCHIVE_SIZE_THRESHOLD must be positive");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException(name + " must be positive");
        }
    }

    /**
     * An S3 bucket of its own: the archive may live in S3 while files stay on a local disk, and the other way round.
     *
     * @param prefix key prefix inside the bucket, e.g. {@code audit/}
     */
    public record S3(
            URI endpoint,
            @DefaultValue("auto") String region,
            String accessKey,
            String secretKey,
            String bucket,
            @DefaultValue("audit/") String prefix,
            @DefaultValue("true") boolean pathStyleAccess
    ) {
        /** The keys stay out of logs and error messages. */
        @Override
        public String toString() {
            return "S3[endpoint=" + endpoint + ", region=" + region + ", bucket=" + bucket + ", prefix=" + prefix + "]";
        }

        /** Checked only when the target is S3, so a local installation needs none of it. */
        public void validate() {
            if (endpoint == null || !endpoint.isAbsolute() || endpoint.getUserInfo() != null
                    || !("http".equalsIgnoreCase(endpoint.getScheme()) || "https".equalsIgnoreCase(endpoint.getScheme()))) {
                throw new IllegalStateException("SMC_AUDIT_ARCHIVE_S3_ENDPOINT must be an absolute HTTP(S) URI without credentials");
            }
            for (var required : new String[][]{{region, "SMC_AUDIT_ARCHIVE_S3_REGION"},
                    {accessKey, "SMC_AUDIT_ARCHIVE_S3_ACCESS_KEY"}, {secretKey, "SMC_AUDIT_ARCHIVE_S3_SECRET_KEY"},
                    {bucket, "SMC_AUDIT_ARCHIVE_S3_BUCKET"}}) {
                if (required[0] == null || required[0].isBlank()) {
                    throw new IllegalStateException(required[1] + " is required when SMC_AUDIT_ARCHIVE_TARGET=s3");
                }
            }
        }
    }
}
