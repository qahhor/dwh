package com.smartup24.cms.instance.webhook.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class WebhookTargetPolicy {

    private static final Logger log = LoggerFactory.getLogger(WebhookTargetPolicy.class);

    private final WebhookProperties properties;
    private final HostResolver hostResolver;

    @Autowired
    public WebhookTargetPolicy(WebhookProperties properties) {
        this(properties, host -> Arrays.asList(InetAddress.getAllByName(host)));
    }

    WebhookTargetPolicy(WebhookProperties properties, HostResolver hostResolver) {
        this.properties = properties;
        this.hostResolver = hostResolver;
    }

    public URI validate(String rawUrl) {
        properties.validate();
        if (!properties.isEnabled()) {
            throw invalid("error.webhook.disabled");
        }

        URI uri;
        try {
            uri = URI.create(rawUrl);
        } catch (RuntimeException exception) {
            throw invalid("error.webhook.url_malformed");
        }

        String scheme = uri.getScheme();
        String host = normalizeHost(uri.getHost());
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw invalid("error.webhook.url_scheme");
        }
        if (host == null || host.isBlank() || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw invalid("error.webhook.url_parts");
        }
        if (uri.getPort() == 0 || uri.getPort() < -1 || uri.getPort() > 65535) {
            throw invalid("error.webhook.url_port");
        }

        Set<String> allowedHosts = properties.getAllowedHosts().stream()
                .map(WebhookTargetPolicy::normalizeHost)
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
        if (!allowedHosts.contains(host)) {
            throw invalid("error.webhook.host_not_allowed");
        }

        List<InetAddress> addresses;
        try {
            addresses = hostResolver.resolve(host);
        } catch (UnknownHostException exception) {
            throw ApiException.badRequest(ErrorCode.WEBHOOK_TARGET_UNREACHABLE, "error.webhook.host_unresolved");
        }
        if (addresses == null || addresses.isEmpty()) {
            throw ApiException.badRequest(ErrorCode.WEBHOOK_TARGET_UNREACHABLE, "error.webhook.host_unresolved");
        }
        if (!properties.isAllowPrivateAddresses()
                && addresses.stream().anyMatch(WebhookTargetPolicy::isSpecialAddress)) {
            throw invalid("error.webhook.host_private");
        }

        return uri;
    }

    public String redact(URI uri) {
        try {
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null).toString();
        } catch (URISyntaxException exception) {
            log.debug("webhook_target_unredactable error={}", exception.toString());
            return "invalid-webhook-target";
        }
    }

    private static String normalizeHost(String host) {
        if (host == null) {
            return null;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static boolean isSpecialAddress(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }

        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            return first == 0
                    || (first == 100 && second >= 64 && second <= 127)
                    || (first == 192 && second == 0)
                    || (first == 198 && (second == 18 || second == 19))
                    || (first == 198 && second == 51)
                    || (first == 203 && second == 0)
                    || first >= 224;
        }

        int first = Byte.toUnsignedInt(bytes[0]);
        int second = Byte.toUnsignedInt(bytes[1]);
        boolean uniqueLocal = (first & 0xfe) == 0xfc;
        boolean documentation = first == 0x20
                && second == 0x01
                && Byte.toUnsignedInt(bytes[2]) == 0x0d
                && Byte.toUnsignedInt(bytes[3]) == 0xb8;
        return uniqueLocal || documentation;
    }

    private static ApiException invalid(String messageKey) {
        return ApiException.badRequest(ErrorCode.INVALID_URL, messageKey);
    }

    @FunctionalInterface
    interface HostResolver {
        List<InetAddress> resolve(String host) throws UnknownHostException;
    }
}
