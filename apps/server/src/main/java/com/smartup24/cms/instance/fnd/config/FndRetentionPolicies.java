package com.smartup24.cms.instance.fnd.config;

import com.smartup24.cms.instance.common.retention.RetentionPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The journals of the core and how long they live (plan 10/10, item 3.13). The load log stays: it is the history of
 * what reached the data warehouse and when.
 */
@Configuration(proxyBeanMethods = false)
public class FndRetentionPolicies {

    /** Finished runs of background jobs; a running one is never deleted. */
    @Bean
    RetentionPolicy jobRunsRetention() {
        return new RetentionPolicy("job-runs", "fnd_job_runs", "finished_at is not null and finished_at < :cutoff", 90);
    }
}
