package com.smartup24.cms.instance.fnd.jobs;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds the retry and lease settings of the job queue ({@code dwh.fnd.jobs.*}). */
@Configuration
@EnableConfigurationProperties(FndJobProperties.class)
public class FndJobsConfig {}
