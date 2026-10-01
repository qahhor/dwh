package com.smartup24.cms.instance.config.security;

import com.smartup24.cms.instance.common.security.StoredSecrets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** The key of the secrets kept in the database, from {@code SMC_SECRETS_KEY} (ADR-0029). */
@Configuration(proxyBeanMethods = false)
public class StoredSecretsConfiguration {

    @Bean
    StoredSecrets storedSecrets(@Value("${smc.secrets.key:}") String key, Environment environment) {
        return StoredSecrets.fromConfiguration(
                key, environment.matchesProfiles("dev"), environment.matchesProfiles("migrate"));
    }
}
