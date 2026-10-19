package com.smartup24.cms.instance.studio.solution;

import com.smartup24.cms.instance.common.retention.RetentionPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** How long a checked package that was never applied is kept (ADR-0047, ADR-0025). */
@Configuration(proxyBeanMethods = false)
public class SolutionRetentionPolicies {

    /** A check expires after a month; an applied import is the history of the installation and stays. */
    @Bean
    RetentionPolicy packagePreviewsRetention() {
        return new RetentionPolicy(
                "package-previews", "studio_package_imports", "status = 'preview' and created_at < :cutoff", 30);
    }
}
