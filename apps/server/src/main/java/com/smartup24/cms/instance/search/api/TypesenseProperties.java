package com.smartup24.cms.instance.search.api;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "smc.typesense")
public record TypesenseProperties(String url, String apiKey, boolean enabled, boolean syncOnStartup) {
    public TypesenseProperties {
        if (url == null || url.isBlank()) {
            url = "http://typesense:8108";
        }
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = "smartupcms_typesense_local_dev";
        }
    }
}
