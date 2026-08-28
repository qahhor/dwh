package com.greenwhite.dwh.instance.kwh;

import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.kwh.repository.KwhOutboxRepository;
import com.greenwhite.dwh.instance.kwh.repository.KwhSubscriptionRepository;
import com.greenwhite.dwh.instance.kwh.service.KwhWebhookService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KwhWebhookServiceTest {

    private final KwhSubscriptionRepository subscriptionRepository = Mockito.mock(KwhSubscriptionRepository.class);
    private final KwhOutboxRepository outboxRepository = Mockito.mock(KwhOutboxRepository.class);
    private final KwhWebhookService service = new KwhWebhookService(subscriptionRepository, outboxRepository);

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
}
