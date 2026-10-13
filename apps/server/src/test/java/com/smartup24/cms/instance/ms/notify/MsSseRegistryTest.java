package com.smartup24.cms.instance.ms.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.common.security.SecurityContext.KauthPrincipal;
import com.smartup24.cms.instance.ms.notify.sse.MsSseAccess;
import com.smartup24.cms.instance.ms.notify.sse.MsSseController;
import com.smartup24.cms.instance.ms.notify.sse.MsSseRegistry;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * FR-NOTIF-2: the registry of SSE connections: delivery, user isolation,
 * the tab limit, and cleanup of dead connections.
 */
class MsSseRegistryTest {

    private final MsSseAccess access = mock(MsSseAccess.class);
    private final MsSseRegistry registry = new MsSseRegistry(60_000, 3, access);

    @Test
    @DisplayName("Подписка открывает поток и сразу шлёт connected")
    void subscribeSendsConnectedEvent() throws Exception {
        var response = open(1L);
        assertThat(registry.openConnectionCount()).isEqualTo(1);
        assertThat(response.getResponse().getContentAsString()).contains("event:connected", "data:ok");
        registry.send(1L, "notification", Map.of("id", 42));
        assertThat(response.getResponse().getContentAsString()).contains("event:notification", "42");
    }

    @Test
    @DisplayName("Уведомление уходит только адресату, чужие потоки не трогаются")
    void sendIsolatesUsers() throws Exception {
        var recipient = open(1L);
        var stranger = open(2L);
        assertThat(registry.openConnectionCount()).isEqualTo(2);
        registry.send(1L, "notification", "recipient-only");
        registry.send(999L, "notification", Map.of("id", 1));
        assertThat(recipient.getResponse().getContentAsString()).contains("recipient-only");
        assertThat(stranger.getResponse().getContentAsString()).doesNotContain("recipient-only", "event:notification");
        assertThat(registry.openConnectionCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("Лимит соединений на пользователя: старые вытесняются, счётчик не растёт")
    void enforcesMaxConnectionsPerUser() {
        for (int i = 0; i < 6; i++) {
            registry.subscribe(principal(7L));
        }
        assertThat(registry.openConnectionCount())
                .as("лимит 3 на пользователя — защита от утечки вкладок")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("Мёртвое соединение вычищается при следующей отправке")
    void deadEmitterIsRemovedOnNextSend() {
        SseEmitter emitter = registry.subscribe(principal(5L));
        assertThat(registry.openConnectionCount()).isEqualTo(1);

        // The client left. In a real request the container calls onCompletion/onError
        // (a unit test has no async context), so this checks the SECOND line
        // of defense: sending to a completed emitter fails and the emitter is removed.
        emitter.complete();
        registry.send(5L, "notification", Map.of("id", 1));

        assertThat(registry.openConnectionCount())
                .as("мёртвое соединение не должно оставаться в реестре")
                .isZero();
    }

    @Test
    @DisplayName("Heartbeat тоже вычищает мёртвые соединения — не копятся между уведомлениями")
    void heartbeatRemovesDeadEmitters() {
        SseEmitter emitter = registry.subscribe(principal(6L));
        emitter.complete();

        registry.sendHeartbeat();

        assertThat(registry.openConnectionCount())
                .as("heartbeat раз в 25 с — гарантия, что мёртвые соединения не живут дольше")
                .isZero();
    }

    @Test
    @DisplayName("Heartbeat не падает и не ломает реестр при отсутствии подписчиков")
    void heartbeatIsSafeWhenEmpty() {
        registry.sendHeartbeat();
        assertThat(registry.openConnectionCount()).isZero();

        registry.subscribe(principal(3L));
        registry.sendHeartbeat();
        assertThat(registry.openConnectionCount()).isEqualTo(1);
    }

    @Test
    void authorizationFailureClosesTheStreamWithoutSendingData() throws Exception {
        var response = open(1L);
        doThrow(new DataAccessResourceFailureException("unavailable"))
                .when(access)
                .requireCurrent(principal(1L));

        registry.send(1L, "notification", "private-data");
        registry.sendHeartbeat();

        assertThat(response.getResponse().getContentAsString()).doesNotContain("private-data", "ping");
        assertThat(registry.openConnectionCount()).isZero();
        assertThat(registry.hasConnections(1L)).isFalse();
    }

    @Test
    void refusingTheInitialProofDoesNotAllocateAStream() {
        doThrow(ApiException.invalidCredentials()).when(access).requireCurrent(principal(1L));

        assertThatThrownBy(() -> registry.subscribe(principal(1L))).isInstanceOf(ApiException.class);

        assertThat(registry.openConnectionCount()).isZero();
    }

    private MvcResult open(Long userId) throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new MsSseController(registry)).build();
        SecurityContext.setPrincipal(principal(userId));
        try {
            return mvc.perform(get("/api/v1/events")).andReturn();
        } finally {
            SecurityContext.clear();
        }
    }

    private static KauthPrincipal principal(Long userId) {
        return new KauthPrincipal(
                userId, "synthetic", "synthetic", userId, false, Set.of("notify.inbox.view"), 1, false, 0, null);
    }
}
