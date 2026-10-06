package com.smartup24.cms.instance.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.webhook.repository.WebhookOutboxRepository;
import com.smartup24.cms.instance.webhook.repository.WebhookSubscriptionRepository;
import com.smartup24.cms.instance.webhook.service.WebhookProperties;
import com.smartup24.cms.instance.webhook.service.WebhookService;
import com.smartup24.cms.instance.webhook.service.WebhookTargetPolicy;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

class WebhookServiceTest {

    private final WebhookSubscriptionRepository subscriptionRepository =
            Mockito.mock(WebhookSubscriptionRepository.class);
    private final WebhookOutboxRepository outboxRepository = Mockito.mock(WebhookOutboxRepository.class);
    private final WebhookService service = new WebhookService(
            subscriptionRepository,
            outboxRepository,
            Mockito.mock(AuditLogService.class),
            policy(true, Set.of("93.184.216.34"), false));

    @Test
    @DisplayName("HMAC-SHA256 подпись должна вычисляться детерминированно")
    void shouldComputeHmacSha256Correctly() {
        String payload = "{\"event\":\"task.created\",\"id\":100}";
        String secretKey = "super_secret_test_key";
        long timestamp = 1775000000L;

        String signature1 = WebhookService.computeHmacSha256(timestamp, payload, secretKey);
        String signature2 = WebhookService.computeHmacSha256(timestamp, payload, secretKey);

        assertThat(signature1).isNotNull().hasSize(64);
        assertThat(signature1).isEqualTo(signature2);
        assertThat(signature1).isEqualTo(WebhookService.computeHmacSha256(timestamp + "." + payload, secretKey));
    }

    @Test
    @DisplayName("Подписка на неизвестное событие должна отклоняться с кодом 422")
    void shouldRejectUnknownSubscribedEvent() {
        assertThatThrownBy(() -> service.createSubscription(
                        "Test", "https://93.184.216.34/events", List.of("unknown.event"), 1L))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.webhook.event_unknown");
    }

    @Test
    @DisplayName("Каталог событий должен содержать wildcard и события сущностей")
    void shouldListAvailableEvents() {
        var events = service.listEvents();
        assertThat(events).isNotEmpty();
        assertThat(events.getFirst().code()).isEqualTo("*");
    }

    @Test
    @DisplayName("Регистрация подписки с некорректным URL должна отклоняться")
    void shouldRejectInvalidTargetUrl() {
        assertThatThrownBy(() -> service.createSubscription("Test", "ftp://invalid-url", List.of("*"), 1L))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.webhook.url_scheme");
    }

    @Test
    @DisplayName("Вебхуки должны быть fail-closed до явного включения оператором")
    void shouldRejectSubscriptionsWhenWebhooksAreDisabled() {
        var disabledService = new WebhookService(
                subscriptionRepository,
                outboxRepository,
                Mockito.mock(AuditLogService.class),
                policy(false, Set.of("hooks.example"), false));

        assertThatThrownBy(() ->
                        disabledService.createSubscription("Test", "https://hooks.example/events", List.of("*"), 1L))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.webhook.disabled");
        Mockito.verifyNoInteractions(subscriptionRepository);
    }

    @Test
    @DisplayName("Изменение адреса подписки должно повторно проходить outbound policy")
    void shouldRevalidateTargetUrlOnUpdate() {
        var privateTargetService = new WebhookService(
                subscriptionRepository,
                outboxRepository,
                Mockito.mock(AuditLogService.class),
                policy(true, Set.of("127.0.0.1"), false));

        assertThatThrownBy(() ->
                        privateTargetService.updateSubscription(10L, null, "http://127.0.0.1/internal", null, null, 1L))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.webhook.host_private");
        Mockito.verifyNoInteractions(subscriptionRepository);
    }

    @Test
    @DisplayName("Signing secret должен возвращаться только один раз при создании подписки")
    void shouldReturnSigningSecretOnlyAtCreation() throws Exception {
        var record = new WebhookSubscriptionRepository.SubscriptionRecord(
                7L,
                "Orders",
                "https://93.184.216.34/events?token=private-query",
                "one-time-signing-secret",
                List.of("*"),
                "A",
                Instant.now(),
                1L,
                1L);
        Mockito.when(subscriptionRepository.create(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.anyList(),
                        Mockito.anyLong()))
                .thenReturn(record);
        Mockito.when(subscriptionRepository.listSubscriptions()).thenReturn(List.of(record));
        var safeService = new WebhookService(
                subscriptionRepository,
                outboxRepository,
                Mockito.mock(AuditLogService.class),
                policy(true, Set.of("93.184.216.34"), false));
        var mapper = new ObjectMapper();

        String createdJson = mapper.writeValueAsString(
                safeService.createSubscription("Orders", record.targetUrl(), record.subscribedEvents(), 1L));
        String listJson = mapper.writeValueAsString(safeService.listSubscriptions());

        assertThat(createdJson).contains("one-time-signing-secret");
        assertThat(listJson).doesNotContain("one-time-signing-secret").doesNotContain("private-query");
    }

    private static WebhookTargetPolicy policy(
            boolean enabled, Set<String> allowedHosts, boolean allowPrivateAddresses) {
        var properties = new WebhookProperties();
        properties.setEnabled(enabled);
        properties.setAllowedHosts(allowedHosts);
        properties.setAllowPrivateAddresses(allowPrivateAddresses);
        return new WebhookTargetPolicy(properties);
    }
}
