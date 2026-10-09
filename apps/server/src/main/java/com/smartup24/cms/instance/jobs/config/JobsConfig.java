package com.smartup24.cms.instance.jobs.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the retry and lease settings of the job queue ({@code smc.jobs.*}). The switch that stops the queue is an
 * instance setting owned by md, so the application wiring provides it ({@code config.jobs.JobSwitchConfiguration}):
 * jobs, an infrastructure module, depends on no business module (plan 10/10, item 1.3).
 */
@Configuration
@EnableConfigurationProperties(JobProperties.class)
public class JobsConfig {}
