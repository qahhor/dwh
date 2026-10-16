package com.smartup24.cms.instance.studio.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext.KauthPrincipal;
import com.smartup24.cms.instance.config.security.RateLimitService;
import com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations;
import com.smartup24.cms.spi.llm.LlmProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class StudioAssistantServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final StudioAssistantAccess access = mock(StudioAssistantAccess.class);
    private final LlmProvider provider = mock(LlmProvider.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private String declaration;

    @BeforeEach
    void setup() throws Exception {
        declaration = Files.readString(Path.of("src/test/resources/studio/simple-form.json"));
        when(access.current())
                .thenReturn(new KauthPrincipal(1L, "test", "", 1L, false, Set.of("*.*"), 1L, false, 0L, null));
        when(provider.getProviderCode()).thenReturn("test");
        when(provider.isConfigured()).thenReturn(true);
        when(provider.generate(anyString(), anyString())).thenReturn(declaration);
    }

    @Test
    void returnsValidatedDeclarationAndReauthorizesAfterExternalWork() throws Exception {
        try (var unused = service(true, "test", Duration.ofSeconds(2))) {
            var result = unused.propose(body("Catalogue"));
            assertThat(result.kind()).isEqualTo("simple-form");
            verify(access, times(2)).current();
            verify(provider).generate(contains("JSON"), eq("Catalogue"));
            assertThat(metrics.get("smc.studio.assistant.requests")
                            .tag("outcome", "success")
                            .counter()
                            .count())
                    .isEqualTo(1);
        }
    }

    @Test
    void disabledUnknownProviderMissingConfigAndDeniedAccessNeverInvokeProvider() throws Exception {
        for (String code : List.of("test", "missing")) {
            var disabled = service(!code.equals("test"), code, Duration.ofSeconds(1));
            assertThat(disabled.availability().available()).isFalse();
            assertThatThrownBy(() -> disabled.propose(body("task"))).isInstanceOf(ApiException.class);
            disabled.close();
        }
        when(provider.isConfigured()).thenReturn(false);
        assertThat(service(true, "test", Duration.ofSeconds(1)).availability().available())
                .isFalse();
        doThrow(ApiException.permissionDenied("studio.forms", "assist"))
                .when(access)
                .requireAccess();
        assertThatThrownBy(() -> service(true, "test", Duration.ofSeconds(1)).propose(body("task")))
                .isInstanceOf(ApiException.class);
        verify(provider, never()).generate(anyString(), anyString());
    }

    @Test
    void providerFailureAndUnsafeOutputNeverReturnRawContent() throws Exception {
        var service = service(true, "test", Duration.ofSeconds(2));
        when(provider.generate(anyString(), anyString())).thenThrow(new java.io.IOException("private provider detail"));
        assertThatThrownBy(() -> service.propose(body("task")))
                .isInstanceOf(ApiException.class)
                .hasMessage("error.studio.assistant_unavailable");
        for (String invalid : List.of("{}", declaration.replace("simple-form", "unknown"), declaration + "{}")) {
            doReturn(invalid).when(provider).generate(anyString(), anyString());
            var fresh = service(true, "test", Duration.ofSeconds(2));
            assertThatThrownBy(() -> fresh.propose(body("task")))
                    .isInstanceOf(ApiException.class)
                    .hasMessage("error.studio.assistant_invalid");
            fresh.close();
        }
        service.close();
    }

    @Test
    void revokedAccessAfterGenerationDiscardsValidOutput() {
        when(access.current())
                .thenReturn(new KauthPrincipal(1L, "test", "", 1L, false, Set.of("*.*"), 1L, false, 0L, null))
                .thenThrow(ApiException.permissionDenied("studio.forms", "assist"));
        var service = service(true, "test", Duration.ofSeconds(2));
        assertThatThrownBy(() -> service.propose(body("task")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("error.permission_denied_action");
        assertThat(metrics.find("smc.studio.assistant.requests")
                        .tag("outcome", "success")
                        .counter())
                .isNull();
        service.close();
    }

    @Test
    void timeoutDoesNotReleaseCapacityWhileProviderIgnoresInterruption() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        when(provider.generate(anyString(), anyString())).thenAnswer(invocation -> {
            boolean done = false;
            while (!done) {
                try {
                    done = release.await(1, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.interrupted();
                }
            }
            return declaration;
        });
        var service = service(true, "test", Duration.ofMillis(30));
        try {
            assertThatThrownBy(() -> service.propose(body("task"))).hasMessage("error.studio.assistant_timeout");
            assertThatThrownBy(() -> service.propose(body("task"))).hasMessage("error.studio.assistant_timeout");
            when(access.current())
                    .thenReturn(new KauthPrincipal(2L, "test", "", 2L, false, Set.of("*.*"), 1L, false, 0L, null));
            assertThatThrownBy(() -> service.propose(body("task"))).hasMessage("error.studio.assistant_busy");
            verify(provider, times(2)).generate(anyString(), anyString());
        } finally {
            release.countDown();
            service.close();
        }
    }

    @Test
    void boundsNullLargeOutputsAndPerUserBudget() throws Exception {
        for (String output : new String[] {null, " ", "x".repeat(32769)}) {
            doReturn(output).when(provider).generate(anyString(), anyString());
            try (var service = service(true, "test", Duration.ofSeconds(2))) {
                assertThatThrownBy(() -> service.propose(body("task"))).hasMessage("error.studio.assistant_invalid");
            }
        }
        doReturn(declaration).when(provider).generate(anyString(), anyString());
        try (var service = service(true, "test", Duration.ofSeconds(2))) {
            service.propose(body("task"));
            service.propose(body("task"));
            assertThatThrownBy(() -> service.propose(body("task"))).hasMessage("error.studio.assistant_busy");
        }
    }

    @Test
    void closedServiceAndShutdownRaceDoNotStartProvider() throws Exception {
        var service = service(true, "test", Duration.ofSeconds(2));
        service.close();
        assertThat(service.availability().available()).isFalse();
        assertThatThrownBy(() -> service.propose(body("task"))).hasMessage("error.studio.assistant_disabled");
        var racing = service(true, "test", Duration.ofSeconds(2));
        when(access.current()).thenAnswer(invocation -> {
            racing.close();
            return new KauthPrincipal(1L, "test", "", 1L, false, Set.of("*.*"), 1L, false, 0L, null);
        });
        assertThatThrownBy(() -> racing.propose(body("task"))).hasMessage("error.studio.assistant_disabled");
        verify(provider, never()).generate(anyString(), anyString());
    }

    @Test
    void interruptedRequestPreservesInterruptAndCancelsProvider() throws Exception {
        when(provider.generate(anyString(), anyString())).thenAnswer(invocation -> {
            Thread.sleep(10000);
            return declaration;
        });
        try (var service = service(true, "test", Duration.ofSeconds(2))) {
            Thread.currentThread().interrupt();
            try {
                assertThatThrownBy(() -> service.propose(body("task")))
                        .hasMessage("error.studio.assistant_unavailable");
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally {
                Thread.interrupted();
            }
        }
    }

    private StudioAssistantService service(boolean enabled, String code, Duration timeout) {
        return new StudioAssistantService(
                access,
                new StudioAssistantInput(json),
                new MdSimpleFormDeclarations(json),
                List.of(provider),
                new RateLimitService(),
                metrics,
                enabled,
                code,
                timeout);
    }

    private byte[] body(String prompt) {
        return json.writeValueAsBytes(Map.of("prompt", prompt));
    }
}
