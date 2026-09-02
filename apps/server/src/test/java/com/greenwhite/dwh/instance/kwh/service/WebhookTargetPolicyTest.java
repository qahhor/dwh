package com.greenwhite.dwh.instance.kwh.service;

import com.greenwhite.dwh.instance.common.error.ApiException;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookTargetPolicyTest {

    @Test
    void rejectsAllTargetsWhileWebhooksAreDisabled() throws Exception {
        var properties = properties(false, Set.of("hooks.example"), false);
        var policy = new WebhookTargetPolicy(properties,
                host -> List.of(InetAddress.getByName("93.184.216.34")));

        assertThatThrownBy(() -> policy.validate("https://hooks.example/events"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("отключены");
    }

    @Test
    void rejectsHostsOutsideTheExplicitAllowList() throws Exception {
        var properties = properties(true, Set.of("hooks.example"), false);
        var policy = new WebhookTargetPolicy(properties,
                host -> List.of(InetAddress.getByName("93.184.216.34")));

        assertThatThrownBy(() -> policy.validate("https://attacker.example/events"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("список разрешённых");
    }

    @Test
    void rejectsLoopbackPrivateLinkLocalAndMetadataDestinations() throws Exception {
        var properties = properties(true, Set.of("hook.example"), false);

        for (String address : List.of("127.0.0.1", "10.0.0.5", "169.254.169.254", "::1", "fd00::1")) {
            var policy = new WebhookTargetPolicy(properties,
                    host -> List.of(InetAddress.getByName(address)));

            assertThatThrownBy(() -> policy.validate("https://hook.example/events"))
                    .as("address %s must not cross the outbound trust boundary", address)
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("внутренний или специальный адрес");
        }
    }

    @Test
    void acceptsOnlyAnAllowListedHostWhoseEveryAddressIsPublic() throws Exception {
        var properties = properties(true, Set.of("HOOKS.EXAMPLE."), false);
        var policy = new WebhookTargetPolicy(properties,
                host -> List.of(
                        InetAddress.getByName("93.184.216.34"),
                        InetAddress.getByName("2606:2800:220:1:248:1893:25c8:1946")));

        var target = policy.validate("https://hooks.example/events?token=secret");

        assertThat(target.getHost()).isEqualTo("hooks.example");
        assertThat(policy.redact(target)).isEqualTo("https://hooks.example/events");
    }

    @Test
    void permitsPrivateDestinationsOnlyAfterAnExplicitOperatorOptIn() throws Exception {
        var properties = properties(true, Set.of("internal-hook.example"), true);
        var policy = new WebhookTargetPolicy(properties,
                host -> List.of(InetAddress.getByName("10.20.30.40")));

        assertThat(policy.validate("http://internal-hook.example:8080/events").toString())
                .isEqualTo("http://internal-hook.example:8080/events");
    }

    @Test
    void rejectsCredentialsFragmentsAndNonHttpSchemes() throws Exception {
        var properties = properties(true, Set.of("hooks.example"), false);
        var policy = new WebhookTargetPolicy(properties,
                host -> List.of(InetAddress.getByName("93.184.216.34")));

        assertThatThrownBy(() -> policy.validate("https://user:secret@hooks.example/events"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> policy.validate("https://hooks.example/events#internal"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> policy.validate("file://hooks.example/etc/passwd"))
                .isInstanceOf(ApiException.class);
    }

    private static KwhWebhookProperties properties(boolean enabled, Set<String> allowedHosts,
                                                    boolean allowPrivateAddresses) {
        var properties = new KwhWebhookProperties();
        properties.setEnabled(enabled);
        properties.setAllowedHosts(allowedHosts);
        properties.setAllowPrivateAddresses(allowPrivateAddresses);
        properties.setConnectTimeout(Duration.ofSeconds(2));
        properties.setReadTimeout(Duration.ofSeconds(5));
        return properties;
    }
}
