package com.smartup24.cms.instance.common.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.security.web.util.matcher.IpAddressMatcher;

/**
 * Safely resolves the client IP address and protocol (FR-SEC-2).
 * <p>
 * Prevents IP spoofing through the {@code X-Forwarded-For} header:
 * <ul>
 *   <li>If the direct connection address (remoteAddr) is NOT in the trusted subnets (trusted-proxies),
 *       X-Forwarded-* headers are ignored entirely and the client gets its real remoteAddr.</li>
 *   <li>If the direct connection address IS in the trusted subnets, X-Forwarded-For is
 *       parsed right to left (from the nearest trusted proxy), skipping trusted internal-network
 *       hops. The first untrusted IP is taken as the authentic client address.</li>
 * </ul>
 */
public class ClientIpResolver {

    private static final String DEFAULT_FALLBACK_IP = "127.0.0.1";
    private static final Pattern IPV4_PATTERN =
            Pattern.compile("^((25[0-5]|(2[0-4]|1\\d|[1-9]|)\\d)\\.){3}(25[0-5]|(2[0-4]|1\\d|[1-9]|)\\d)$");

    private final List<IpAddressMatcher> matchers;

    public ClientIpResolver(TrustedProxyProperties properties) {
        List<String> subnets = (properties != null
                        && properties.trustedProxies() != null
                        && !properties.trustedProxies().isEmpty())
                ? properties.trustedProxies()
                : TrustedProxyProperties.DEFAULT_TRUSTED_PROXIES;

        this.matchers = new ArrayList<>();
        for (String subnet : subnets) {
            String trimmed = subnet.trim();
            if (!trimmed.isBlank()) {
                this.matchers.add(new IpAddressMatcher(trimmed));
            }
        }
    }

    /**
     * Checks whether the given IP address is in the trusted proxy list.
     */
    public boolean isTrustedProxy(String ip) {
        if (ip == null || ip.isBlank() || !isValidIp(ip)) {
            return false;
        }
        String clean = normalize(ip);
        for (IpAddressMatcher matcher : matchers) {
            if (matcher.matches(clean)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Extracts the real client IP address, taking trusted proxies into account.
     */
    public String resolveClientIp(HttpServletRequest request) {
        if (request == null) {
            return DEFAULT_FALLBACK_IP;
        }

        String remoteAddr = normalize(request.getRemoteAddr());
        if (remoteAddr == null || remoteAddr.isBlank() || !isValidIp(remoteAddr)) {
            return DEFAULT_FALLBACK_IP;
        }

        // If the direct peer is not a trusted proxy, ignore all forwarding headers
        if (!isTrustedProxy(remoteAddr)) {
            return remoteAddr;
        }

        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) {
            return remoteAddr;
        }

        String[] hops = forwarded.split(",");
        // Walk right to left: from the nearest proxy towards the client
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = normalize(hops[i]);
            if (hop == null || hop.isBlank() || !isValidIp(hop)) {
                continue;
            }
            if (!isTrustedProxy(hop)) {
                // The first untrusted IP from the right is the real client
                return hop;
            }
        }

        // If every hop is trusted (for example, internal infrastructure), take the leftmost valid IP
        for (String hopStr : hops) {
            String hop = normalize(hopStr);
            if (hop != null && !hop.isBlank() && isValidIp(hop)) {
                return hop;
            }
        }

        return remoteAddr;
    }

    /**
     * Determines whether the connection is secure (HTTPS), trusting X-Forwarded-Proto
     * only when the request comes through a trusted proxy.
     */
    public boolean isSecure(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        if (request.isSecure()) {
            return true;
        }

        String remoteAddr = normalize(request.getRemoteAddr());
        if (remoteAddr != null && isTrustedProxy(remoteAddr)) {
            String proto = request.getHeader("X-Forwarded-Proto");
            if (proto != null && !proto.isBlank()) {
                String firstProto = proto.split(",")[0].trim();
                return "https".equalsIgnoreCase(firstProto);
            }
        }

        return false;
    }

    private static @Nullable String normalize(@Nullable String ip) {
        if (ip == null) {
            return null;
        }
        String clean = ip.trim();
        // Strip the IPv6 scope id (for example, fe80::1%eth0)
        int percentIdx = clean.indexOf('%');
        if (percentIdx > 0) {
            clean = clean.substring(0, percentIdx);
        }
        return clean;
    }

    private static boolean isValidIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return false;
        }
        String clean = ip.trim();
        if (IPV4_PATTERN.matcher(clean).matches()) {
            return true;
        }
        // IPv6 format check
        return clean.contains(":") && clean.matches("^[0-9a-fA-F:]+$") && !clean.contains(":::");
    }
}
