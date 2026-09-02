package com.greenwhite.dwh.instance.kwh.service;

import com.greenwhite.dwh.core.error.ErrorCode;
import com.greenwhite.dwh.instance.common.error.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class WebhookTargetPolicy {

    private final KwhWebhookProperties properties;
    private final HostResolver hostResolver;

    @Autowired
    public WebhookTargetPolicy(KwhWebhookProperties properties) {
        this(properties, host -> Arrays.asList(InetAddress.getAllByName(host)));
    }

    WebhookTargetPolicy(KwhWebhookProperties properties, HostResolver hostResolver) {
        this.properties = properties;
        this.hostResolver = hostResolver;
    }

    public URI validate(String rawUrl) {
        properties.validate();
        if (!properties.isEnabled()) {
            throw invalid("Исходящие вебхуки отключены оператором");
        }

        URI uri;
        try {
            uri = URI.create(rawUrl);
        } catch (RuntimeException exception) {
            throw invalid("Некорректный формат URL вебхука");
        }

        String scheme = uri.getScheme();
        String host = normalizeHost(uri.getHost());
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw invalid("URL вебхука должен начинаться с http:// или https://");
        }
        if (host == null || host.isBlank() || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw invalid("URL вебхука не должен содержать credentials или fragment и обязан иметь host");
        }
        if (uri.getPort() == 0 || uri.getPort() < -1 || uri.getPort() > 65535) {
            throw invalid("Некорректный порт URL вебхука");
        }

        Set<String> allowedHosts = properties.getAllowedHosts().stream()
                .map(WebhookTargetPolicy::normalizeHost)
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
        if (!allowedHosts.contains(host)) {
            throw invalid("Host вебхука не входит в список разрешённых оператором");
        }

        List<InetAddress> addresses;
        try {
            addresses = hostResolver.resolve(host);
        } catch (UnknownHostException exception) {
            throw ApiException.badRequest(ErrorCode.WEBHOOK_TARGET_UNREACHABLE,
                    "Host вебхука не разрешается через DNS");
        }
        if (addresses == null || addresses.isEmpty()) {
            throw ApiException.badRequest(ErrorCode.WEBHOOK_TARGET_UNREACHABLE,
                    "Host вебхука не разрешается через DNS");
        }
        if (!properties.isAllowPrivateAddresses() && addresses.stream().anyMatch(WebhookTargetPolicy::isSpecialAddress)) {
            throw invalid("Host вебхука разрешается во внутренний или специальный адрес");
        }

        return uri;
    }

    public String redact(URI uri) {
        try {
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null).toString();
        } catch (Exception exception) {
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
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
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
        boolean documentation = first == 0x20 && second == 0x01
                && Byte.toUnsignedInt(bytes[2]) == 0x0d && Byte.toUnsignedInt(bytes[3]) == 0xb8;
        return uniqueLocal || documentation;
    }

    private static ApiException invalid(String message) {
        return ApiException.badRequest(ErrorCode.INVALID_URL, message);
    }

    @FunctionalInterface
    interface HostResolver {
        List<InetAddress> resolve(String host) throws UnknownHostException;
    }
}
