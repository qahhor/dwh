package com.smartup24.cms.instance.search.service;

import com.smartup24.cms.instance.common.retention.RetentionPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The journal of search index jobs and how long it lives (plan 10/10, item 3.13, ADR-0025). */
@Configuration(proxyBeanMethods = false)
public class SearchRetentionPolicies {

    /**
     * Finished jobs only. A job a retry points at stays until the retry goes, and the newest job always stays: the
     * worker starts the first index build when the index was never built and no job exists at all.
     */
    @Bean
    RetentionPolicy searchJobsRetention() {
        return new RetentionPolicy(
                "search-jobs",
                "search_jobs",
                "state in ('SUCCEEDED', 'FAILED', 'CANCELLED') and finished_at < :cutoff"
                        + " and not exists (select 1 from search_jobs retry where retry.retry_of_job_id = search_jobs.id)"
                        + " and exists (select 1 from search_jobs newer where newer.created_at > search_jobs.created_at)",
                90);
    }
}
