package com.smartup24.cms.instance.studio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.smartup24.cms.instance.md.service.MdPermissionService;
import com.smartup24.cms.instance.md.service.ModuleRegistryService;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import com.smartup24.cms.instance.support.TestSession;
import com.smartup24.cms.instance.support.TestUsers;
import com.smartup24.cms.spi.llm.LlmProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.context.WebApplicationContext;

/** Real sessions and PostgreSQL verify revocation across an external call, without external traffic (ADR-0039). */
@Import(StudioAssistantAccessHttpTest.FixtureConfiguration.class)
@TestPropertySource(properties = {"smc.studio.assistant.enabled=true", "smc.studio.assistant.provider=fixture"})
class StudioAssistantAccessHttpTest extends EmbeddedPostgresTest {
    private static final String URL = "/api/v1/studio/assistant/drafts";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private FixtureProvider provider;

    private TestUsers.TestUser author;
    private TestSession session;

    @BeforeEach
    void setup() throws Exception {
        provider.calls.set(0);
        provider.callback = () -> {};
        provider.output = Files.readString(Path.of("src/test/resources/studio/simple-form.json"));
        var users = TestUsers.of(context);
        author = users.withRights(
                Map.of("md.settings", List.of("update"), "studio.forms", List.of("assist")), users.unit("assistant"));
        session = TestSession.signIn(context, author.login());
    }

    @Test
    void suggestionIsValidatedButDoesNotSavePublishOrPrepare() throws Exception {
        long forms = count("studio_forms"), replays = count("idempotency_keys");
        var response = session.send(post(URL), Map.of("prompt", "Prepare a catalogue"), null);
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(TestSession.object(response))
                .containsEntry("kind", "simple-form")
                .doesNotContainKeys("checksum", "entityCode");
        assertThat(count("studio_forms")).isEqualTo(forms);
        assertThat(count("idempotency_keys")).isEqualTo(replays);
        assertThat(provider.calls.get()).isEqualTo(1);
    }

    @Test
    void bothRightsAreRequiredAndReplayIsNotSupported() throws Exception {
        var users = TestUsers.of(context);
        var onlyAssist = users.withRights(Map.of("studio.forms", List.of("assist")), author.unit());
        var limited = TestSession.signIn(context, onlyAssist.login());
        assertThat(limited.send(get("/api/v1/studio/assistant")).getStatus()).isEqualTo(403);
        assertThat(limited.send(post(URL), Map.of("prompt", "task"), null).getStatus())
                .isEqualTo(403);
        assertThat(session.send(post(URL), Map.of("prompt", "task")).getStatus())
                .isEqualTo(400);
        assertThat(provider.calls.get()).isZero();
    }

    @Test
    void permissionRevokedDuringGenerationDiscardsOutputInTheSameSession() throws Exception {
        provider.callback = () -> {
            jdbc.sql(
                            "delete from md_role_permissions where form_code='studio.forms' and action='assist' and role_id in (select role_id from md_user_roles where user_id=:user)")
                    .param("user", author.id())
                    .update();
            context.getBean(MdPermissionService.class).recalculateEffectivePermissions(author.id());
        };
        assertThat(session.send(post(URL), Map.of("prompt", "task"), null).getStatus())
                .isEqualTo(403);
        assertThat(provider.calls.get()).isEqualTo(1);
    }

    @Test
    void moduleDisabledDuringGenerationDiscardsOutput() throws Exception {
        var modules = context.getBean(ModuleRegistryService.class);
        provider.callback = () -> modules.toggleModuleStatus("studio", false);
        try {
            assertThat(session.send(post(URL), Map.of("prompt", "task"), null).getStatus())
                    .isEqualTo(404);
            assertThat(provider.calls.get()).isEqualTo(1);
        } finally {
            modules.toggleModuleStatus("studio", true);
        }
    }

    @Test
    void invalidInputAndInvalidModelOutputStayClosed() throws Exception {
        assertThat(session.send(post(URL), Map.of("prompt", "task", "rows", List.of()), null)
                        .getStatus())
                .isEqualTo(422);
        assertThat(provider.calls.get()).isZero();
        provider.output = "{\"sql\":\"select 1\"}";
        var response = session.send(post(URL), Map.of("prompt", "task"), null);
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).doesNotContain("select 1");
    }

    private long count(String table) {
        return jdbc.sql("select count(*) from " + table).query(Long.class).single();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixtureConfiguration {
        @Bean
        FixtureProvider fixtureLlmProvider() {
            return new FixtureProvider();
        }
    }

    static class FixtureProvider implements LlmProvider {
        final AtomicInteger calls = new AtomicInteger();
        volatile Runnable callback = () -> {};
        volatile String output = "{}";

        @Override
        public String getProviderCode() {
            return "fixture";
        }

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public String generate(String instructions, String prompt) {
            calls.incrementAndGet();
            callback.run();
            return output;
        }
    }
}
