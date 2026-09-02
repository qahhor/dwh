package com.greenwhite.dwh.instance.kwh.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

@ConfigurationProperties(prefix = "dwh.webhooks")
public class KwhWebhookProperties {

    private boolean enabled;
    private Set<String> allowedHosts = new LinkedHashSet<>();
    private boolean allowPrivateAddresses;
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration readTimeout = Duration.ofSeconds(10);

    public void validate() {
        requirePositive(connectTimeout, "DWH_WEBHOOKS_CONNECT_TIMEOUT");
        requirePositive(readTimeout, "DWH_WEBHOOKS_READ_TIMEOUT");
    }

    private static void requirePositive(Duration value, String environmentName) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException(environmentName + " must be greater than zero");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Set<String> getAllowedHosts() {
        return allowedHosts;
    }

    public void setAllowedHosts(Set<String> allowedHosts) {
        this.allowedHosts = allowedHosts != null ? allowedHosts : new LinkedHashSet<>();
    }

    public boolean isAllowPrivateAddresses() {
        return allowPrivateAddresses;
    }

    public void setAllowPrivateAddresses(boolean allowPrivateAddresses) {
        this.allowPrivateAddresses = allowPrivateAddresses;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }
}
