package com.greenwhite.dwh.instance.kwh;

import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.kwh.repository.KwhOutboxRepository;
import com.greenwhite.dwh.instance.kwh.repository.KwhSubscriptionRepository;
import com.greenwhite.dwh.instance.kwh.service.KwhWebhookProperties;
import com.greenwhite.dwh.instance.kwh.service.KwhWebhookService;
import com.greenwhite.dwh.instance.kwh.service.WebhookTargetPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KwhWebhookServiceTest {

    private final KwhSubscriptionRepository subscriptionRepository = Mockito.mock(KwhSubscriptionRepository.class);
    private final KwhOutboxRepository outboxRepository = Mockito.mock(KwhOutboxRepository.class);
    private final KwhWebhookService service = new KwhWebhookService(subscriptionRepository, outboxRepository,
            Mockito.mock(com.greenwhite.dwh.instance.audit.service.AuditLogService.class),
            policy(true, Set.of("hooks.example"), false));

    @Test
    @DisplayName("HMAC-SHA256 подпись должна вычисляться детерминированно")
    void shouldComputeHmacSha256Correctly() {
        String payload = "{\"event\":\"task.created\",\"id\":100}";
        String secretKey = "super_secret_test_key";

        String signature1 = KwhWebhookService.computeHmacSha256(payload, secretKey);
        String signature2 = KwhWebhookService.computeHmacSha256(payload, secretKey);

        assertThat(signature1).isNotNull().hasSize(64);
        assertThat(signature1).isEqualTo(signature2);
    }

    @Test
    @DisplayName("Регистрация подписки с некорректным URL должна отклоняться")
    void shouldRejectInvalidTargetUrl() {
        assertThatThrownBy(() -> service.createSubscription("Test", "ftp://invalid-url", List.of("task.created"), 1L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("URL вебхука должен начинаться с http:// или https://");
    }

    @Test
    @DisplayName("Вебхуки должны быть fail-closed до явного включения оператором")
    void shouldRejectSubscriptionsWhenWebhooksAreDisabled() {
        var disabledService = new KwhWebhookService(subscriptionRepository, outboxRepository,
                Mockito.mock(com.greenwhite.dwh.instance.audit.service.AuditLogService.class),
                policy(false, Set.of("hooks.example"), false));

        assertThatThrownBy(() -> disabledService.createSubscription(
                "Test", "https://hooks.example/events", List.of("task.created"), 1L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("отключены");
        Mockito.verifyNoInteractions(subscriptionRepository);
    }

    @Test
    @DisplayName("Изменение адреса подписки должно повторно проходить outbound policy")
    void shouldRevalidateTargetUrlOnUpdate() {
        var privateTargetService = new KwhWebhookService(subscriptionRepository, outboxRepository,
                Mockito.mock(com.greenwhite.dwh.instance.audit.service.AuditLogService.class),
                policy(true, Set.of("127.0.0.1"), false));

        assertThatThrownBy(() -> privateTargetService.updateSubscription(
                10L, null, "http://127.0.0.1/internal", null, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("внутренний или специальный адрес");
        Mockito.verifyNoInteractions(subscriptionRepository);
    }

    @Test
    @DisplayName("Signing secret должен возвращаться только один раз при создании подписки")
    void shouldReturnSigningSecretOnlyAtCreation() throws Exception {
        var record = new KwhSubscriptionRepository.SubscriptionRecord(
                7L, "Orders", "https://93.184.216.34/events?token=private-query",
                "one-time-signing-secret", List.of("task.created"), "A", Instant.now(), 1L);
        Mockito.when(subscriptionRepository.create(
                        Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyList(), Mockito.anyLong()))
                .thenReturn(record);
        Mockito.when(subscriptionRepository.listSubscriptions()).thenReturn(List.of(record));
        var safeService = new KwhWebhookService(subscriptionRepository, outboxRepository,
                Mockito.mock(com.greenwhite.dwh.instance.audit.service.AuditLogService.class),
                policy(true, Set.of("93.184.216.34"), false));
        var mapper = new ObjectMapper();

        String createdJson = mapper.writeValueAsString(safeService.createSubscription(
                "Orders", record.targetUrl(), record.subscribedEvents(), 1L));
        String listJson = mapper.writeValueAsString(safeService.listSubscriptions());

        assertThat(createdJson).contains("one-time-signing-secret");
        assertThat(listJson)
                .doesNotContain("one-time-signing-secret")
                .doesNotContain("private-query");
    }

    private static WebhookTargetPolicy policy(boolean enabled, Set<String> allowedHosts,
                                              boolean allowPrivateAddresses) {
        var properties = new KwhWebhookProperties();
        properties.setEnabled(enabled);
        properties.setAllowedHosts(allowedHosts);
        properties.setAllowPrivateAddresses(allowPrivateAddresses);
        return new WebhookTargetPolicy(properties);
    }
}
