package com.greenwhite.dwh.cp.instance;

import com.greenwhite.dwh.cp.error.CpApiException;
import com.greenwhite.dwh.cp.instance.api.CpHeartbeatRequest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CpHeartbeatServiceTest {

    @Test
    void serializationFailureUsesStableErrorAndDoesNotPersist() throws Exception {
        var repository = Mockito.mock(CpHeartbeatRepository.class);
        var objectMapper = Mockito.mock(ObjectMapper.class);
        var components = new CpHeartbeatRequest.ComponentHealth(
                CpHeartbeatRequest.Health.UP,
                CpHeartbeatRequest.Health.UP,
                CpHeartbeatRequest.Health.UNKNOWN,
                CpHeartbeatRequest.Health.UNKNOWN);
        var request = new CpHeartbeatRequest(
                "1.2.3", "006", null, null, components,
                null, null, null, null, null);
        Mockito.when(objectMapper.writeValueAsString(components))
                .thenThrow(new IllegalStateException("serialization failed"));
        var service = new CpHeartbeatService(repository, objectMapper);

        assertThatThrownBy(() -> service.recordHeartbeat(
                new CpInstancePrincipal(11, 7, "alpha", 31), request))
                .isInstanceOfSatisfying(CpApiException.class, error -> {
                    assertThat(error.status().value()).isEqualTo(500);
                    assertThat(error.errorCode()).isEqualTo("telemetry_serialization_failed");
                });
        Mockito.verifyNoInteractions(repository);
    }
}
