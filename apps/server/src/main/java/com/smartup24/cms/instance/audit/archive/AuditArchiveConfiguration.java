package com.smartup24.cms.instance.audit.archive;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The archive store chosen by {@code smc.audit.archive.target}: a local directory or an S3 bucket. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuditArchiveProperties.class)
public class AuditArchiveConfiguration {

    /** The S3 store is closed with the context (its client is private to it). */
    @Bean
    AuditArchiveStore auditArchiveStore(AuditArchiveProperties properties) {
        // S3 settings are read and checked only for an S3 target: a local installation needs none.
        return AuditArchiveProperties.S3_TARGET.equals(properties.target())
                ? S3AuditArchiveStore.from(properties.s3())
                : new LocalAuditArchiveStore(properties.localPath());
    }
}
