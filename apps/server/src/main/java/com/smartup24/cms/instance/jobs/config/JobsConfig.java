package com.smartup24.cms.instance.jobs.config;

import com.smartup24.cms.instance.jobs.runner.JobSwitch;
import com.smartup24.cms.instance.md.service.MdSettingService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the retry and lease settings of the job queue ({@code smc.jobs.*}) and the switch that stops it. The switch is
 * an instance setting, so it is read through the settings' owner, not from its table (ADR-0026, plan 10/10, item 4.2).
 */
@Configuration
@EnableConfigurationProperties(JobProperties.class)
public class JobsConfig {

    /** {@code jobs_enabled} in the instance settings, read before every claim; with no such setting, jobs run. */
    @Bean
    JobSwitch jobSwitch(MdSettingService settings) {
        return () ->
                JobSwitch.enabled(settings.getInstanceSetting(JobSwitch.KEY).orElse(null));
    }
}
