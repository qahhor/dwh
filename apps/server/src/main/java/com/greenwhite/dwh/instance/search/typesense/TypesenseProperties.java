package com.greenwhite.dwh.instance.search.typesense;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "dwh.typesense")
public record TypesenseProperties(
        String url,
        String apiKey,
        boolean enabled,
        boolean syncOnStartup
) {
    public TypesenseProperties {
        if (url == null || url.isBlank()) {
            url = "http://typesense:8108";
        }
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = "dwh_typesense_local_dev_key";
        }
    }
}
