package com.smartup24.cms.instance.config.jobs;

import com.smartup24.cms.instance.jobs.api.JobSwitch;
import com.smartup24.cms.instance.md.service.MdSettingService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The switch that stops the job queue: {@code jobs_enabled} in the instance settings, read before every claim through
 * the settings' owner, not from its table (ADR-0026). It lives in the application wiring, so the jobs module depends
 * on no business module (plan 10/10, item 1.3).
 */
@Configuration
public class JobSwitchConfiguration {

    /** With no such setting, jobs run. */
    @Bean
    JobSwitch jobSwitch(MdSettingService settings) {
        return () ->
                JobSwitch.enabled(settings.getInstanceSetting(JobSwitch.KEY).orElse(null));
    }
}
