package com.smartup24.cms.instance.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.ms.note.service.MsNoteEntity;
import com.smartup24.cms.instance.webhook.repository.WebhookOutboxRepository;
import com.smartup24.cms.instance.webhook.repository.WebhookSubscriptionRepository;
import com.smartup24.cms.instance.webhook.service.WebhookEventCatalog;
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
    /** The events of the notes: notes.created, notes.updated, notes.deleted, notes.archived, notes.restored. */
    private static final WebhookEventCatalog EVENTS = new WebhookEventCatalog(List.of(MsNoteEntity.DEFINITION));

    private final WebhookService service = new WebhookService(
            subscriptionRepository,
            outboxRepository,
            Mockito.mock(AuditLogService.class),
            policy(true, Set.of("hooks.example"), false),
            EVENTS);

    @Test
    @DisplayName("HMAC-SHA256 подпись должна вычисляться детерминированно")
    void shouldComputeHmacSha256Correctly() {
        String payload = "{\"event\":\"task.created\",\"id\":100}";
        String secretKey = "super_secret_test_key";

        String signature1 = WebhookService.computeHmacSha256(payload, secretKey);
        String signature2 = WebhookService.computeHmacSha256(payload, secretKey);

        assertThat(signature1).isNotNull().hasSize(64);
        assertThat(signature1).isEqualTo(signature2);
    }

    @Test
    @DisplayName("The signature covers the timestamp: the same body at another moment signs differently")
    void theSignatureCoversTheTimestamp() {
        String body = "{\"type\":\"notes.updated\"}";

        assertThat(WebhookService.signature(1_700_000_000L, body, "key"))
                .isEqualTo(WebhookService.computeHmacSha256("1700000000." + body, "key"))
                .isNotEqualTo(WebhookService.signature(1_700_000_001L, body, "key"))
                .isNotEqualTo(WebhookService.computeHmacSha256(body, "key"));
    }

    @Test
    @DisplayName("A subscription to an event no entity publishes is 422 at the position of each unknown one")
    void unknownEventsAreRefused() {
        assertThatThrownBy(() -> service.createSubscription(
                        "Test", "https://hooks.example/events", List.of("notes.updated", "task.created"), 1L))
                .isInstanceOfSatisfying(ApiException.class, refused -> {
                    assertThat(refused.getErrorCode().getDefaultStatus()).isEqualTo(422);
                    assertThat(refused.getMessageKey()).isEqualTo("error.webhook.subscription_invalid");
                    assertThat(refused.getFieldErrors()).singleElement().satisfies(error -> {
                        assertThat(error.field()).isEqualTo("subscribedEvents[1]");
                        assertThat(error.code()).isEqualTo(WebhookEventCatalog.UNKNOWN_EVENT);
                    });
                });
        assertThatThrownBy(() -> service.updateSubscription(7L, null, null, List.of("notes.posted"), null, 1L))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.webhook.subscription_invalid");
        Mockito.verifyNoInteractions(subscriptionRepository);
    }

    @Test
    @DisplayName("Регистрация подписки с некорректным URL должна отклоняться")
    void shouldRejectInvalidTargetUrl() {
        assertThatThrownBy(() -> service.createSubscription("Test", "ftp://invalid-url", List.of("notes.updated"), 1L))
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
                policy(false, Set.of("hooks.example"), false),
                EVENTS);

        assertThatThrownBy(() -> disabledService.createSubscription(
                        "Test", "https://hooks.example/events", List.of("notes.updated"), 1L))
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
                policy(true, Set.of("127.0.0.1"), false),
                EVENTS);

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
                List.of("notes.updated"),
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
                policy(true, Set.of("93.184.216.34"), false),
                EVENTS);
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
