package com.smartup24.cms.instance.webhook.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.instance.common.error.ApiException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The outbound policy of webhook targets, rule by rule: the shape of the address, the allow list, name resolution and
 * the reserved address ranges a delivery must never reach (SSRF).
 */
class WebhookTargetPolicyRulesTest {

    private static final String HOST = "hooks.example";

    @Test
    @DisplayName("A malformed address, a bad port and a fragment are refused before any lookup")
    void shapeOfTheAddress() {
        var policy = policy(false, host -> {
            throw new AssertionError("no lookup for a refused address");
        });

        assertRefused(policy, "https://hooks .example/x", "error.webhook.url_malformed");
        assertRefused(policy, "https://hooks.example:0/x", "error.webhook.url_port");
        assertRefused(policy, "https://hooks.example/x#part", "error.webhook.url_parts");
        assertRefused(policy, "https://user@hooks.example/x", "error.webhook.url_parts");
        assertRefused(policy, "mailto:hooks@hooks.example", "error.webhook.url_scheme");
        assertRefused(policy, "/relative/path", "error.webhook.url_scheme");
        assertRefused(policy, "https://other.example/x", "error.webhook.host_not_allowed");
    }

    @Test
    @DisplayName("The host is matched without case and trailing dots, and a public address passes")
    void normalisedHostWithPublicAddressPasses() {
        var policy = policy(false, host -> List.of(InetAddress.getByAddress(host, new byte[] {93, (byte) 184, 1, 1})));

        assertThat(policy.validate("HTTPS://Hooks.Example./events").getHost()).isEqualTo("Hooks.Example.");
        assertThat(policy.validate("http://hooks.example:8443/events").getPort())
                .isEqualTo(8443);
    }

    @Test
    @DisplayName("A host that does not resolve, or resolves to nothing, is unreachable")
    void unresolvedHostIsUnreachable() {
        var unknown = policy(false, host -> {
            throw new UnknownHostException(host);
        });
        var empty = policy(false, host -> List.of());
        var none = policy(false, host -> null);

        for (var policy : List.of(unknown, empty, none)) {
            assertThatThrownBy(() -> policy.validate("https://hooks.example/x"))
                    .isInstanceOf(ApiException.class)
                    .hasFieldOrPropertyWithValue("messageKey", "error.webhook.host_unresolved");
        }
    }

    @Test
    @DisplayName("Every reserved IPv4 and IPv6 range is refused unless private targets are allowed")
    void reservedRangesAreRefused() throws Exception {
        List<String> reserved = List.of(
                "0.1.2.3",
                "100.64.0.1",
                "100.127.255.254",
                "192.0.0.8",
                "198.18.0.1",
                "198.19.0.1",
                "198.51.100.7",
                "203.0.113.9",
                "224.0.0.1",
                "240.0.0.1",
                "10.0.0.1",
                "169.254.169.254",
                "fc00::1",
                "fd12:3456::1",
                "2001:db8::1",
                "::1",
                "fe80::1");
        for (String address : reserved) {
            InetAddress resolved = InetAddress.getByName(address);
            assertThatThrownBy(() -> policy(false, host -> List.of(resolved)).validate("https://hooks.example/x"))
                    .as(address)
                    .isInstanceOf(ApiException.class)
                    .hasFieldOrPropertyWithValue("messageKey", "error.webhook.host_private");
            assertThat(policy(true, host -> List.of(resolved)).validate("https://hooks.example/x"))
                    .as(address + " with private targets allowed")
                    .isNotNull();
        }
        InetAddress publicV6 = InetAddress.getByName("2606:4700::1111");
        InetAddress publicV4 = InetAddress.getByName("100.128.0.1");
        assertThat(policy(false, host -> List.of(publicV6, publicV4)).validate("https://hooks.example/x"))
                .isNotNull();
    }

    @Test
    @DisplayName("One reserved address among public ones refuses the target")
    void oneReservedAddressRefusesTheTarget() throws Exception {
        InetAddress publicAddress = InetAddress.getByName("93.184.216.34");
        InetAddress loopback = InetAddress.getByName("127.0.0.1");

        assertRefused(
                policy(false, host -> List.of(publicAddress, loopback)),
                "https://hooks.example/x",
                "error.webhook.host_private");
    }

    @Test
    @DisplayName("The redacted address keeps scheme, host, port and path, and drops credentials and the query")
    void redactionDropsSecrets() {
        var policy = policy(false, host -> List.of());

        assertThat(policy.redact(URI.create("https://user:pw@hooks.example:8443/a/b?token=secret#frag")))
                .isEqualTo("https://hooks.example:8443/a/b");
    }

    @Test
    @DisplayName("A zero timeout is a configuration error, reported before any target is checked")
    void zeroTimeoutIsAConfigurationError() {
        var properties = properties(false);
        properties.setConnectTimeout(Duration.ZERO);
        var policy = new WebhookTargetPolicy(properties, host -> List.of());

        assertThatThrownBy(() -> policy.validate("https://hooks.example/x")).isInstanceOf(IllegalStateException.class);
    }

    private static void assertRefused(WebhookTargetPolicy policy, String url, String key) {
        assertThatThrownBy(() -> policy.validate(url))
                .as(url)
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("messageKey", key);
    }

    private static WebhookTargetPolicy policy(boolean allowPrivate, WebhookTargetPolicy.HostResolver resolver) {
        return new WebhookTargetPolicy(properties(allowPrivate), resolver);
    }

    private static WebhookProperties properties(boolean allowPrivate) {
        var properties = new WebhookProperties();
        properties.setEnabled(true);
        properties.setAllowedHosts(Set.of(HOST, "HOOKS.EXAMPLE.", " "));
        properties.setAllowPrivateAddresses(allowPrivate);
        return properties;
    }
}
