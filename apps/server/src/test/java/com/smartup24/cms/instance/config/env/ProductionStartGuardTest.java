package com.smartup24.cms.instance.config.env;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.env.MockEnvironment;

/** Outside the dev and test profiles a development-only setting stops the start (ADR-0027). */
class ProductionStartGuardTest {

    private final ProductionStartGuard guard = new ProductionStartGuard();

    /** A production environment that passes: its own address, SMTP, both schema gates on. */
    private static MockEnvironment production() {
        return new MockEnvironment()
                .withProperty("smc.public-url", "https://cms.example.test")
                .withProperty("smc.providers.mail", "smtp")
                .withProperty("smc.delivery.enforce", "true");
    }

    @Test
    @DisplayName("A configured production environment starts")
    void configuredProductionStarts() {
        assertThatCode(() -> guard.postProcessEnvironment(production(), new SpringApplication()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Without SMC_PUBLIC_URL the start is refused: no invitation or reset link could be sent")
    void publicUrlIsRequired() {
        MockEnvironment environment = production().withProperty("smc.public-url", " ");

        assertThatThrownBy(() -> guard.postProcessEnvironment(environment, new SpringApplication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SMC_PUBLIC_URL");
    }

    @Test
    @DisplayName("The stub mail provider is refused while delivery is enforced, accepted when it is not")
    void stubMailNeedsDeliveryOff() {
        MockEnvironment stub = production().withProperty("smc.providers.mail", "console_mail");
        assertThatThrownBy(() -> guard.postProcessEnvironment(stub, new SpringApplication()))
                .hasMessageContaining("SMC_PROVIDER_MAIL=console_mail")
                .hasMessageContaining("SMC_DELIVERY_ENFORCE=false");

        MockEnvironment knowingly = production()
                .withProperty("smc.providers.mail", "console_mail")
                .withProperty("smc.delivery.enforce", "false");
        assertThatCode(() -> guard.postProcessEnvironment(knowingly, new SpringApplication()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("A schema gate switched off is refused outside dev and test")
    void schemaGatesStayOn() {
        MockEnvironment environment = production()
                .withProperty("smc.entities.schema-gate-enabled", "false")
                .withProperty("smc.schema-gate.enabled", "false");

        assertThatThrownBy(() -> guard.postProcessEnvironment(environment, new SpringApplication()))
                .hasMessageContaining("SMC_ENTITIES_SCHEMA_GATE_ENABLED")
                .hasMessageContaining("SMC_SCHEMA_GATE_ENABLED");
    }

    @Test
    @DisplayName("The demo profile runs only together with dev or test")
    void demoNeedsDevelopment() {
        MockEnvironment alone = production();
        alone.setActiveProfiles("demo");
        assertThatThrownBy(() -> guard.postProcessEnvironment(alone, new SpringApplication()))
                .hasMessageContaining("demo");

        MockEnvironment withMigrate = new MockEnvironment();
        withMigrate.setActiveProfiles("migrate", "demo");
        assertThat(ProductionStartGuard.problems(withMigrate)).hasSize(1);

        for (String development : new String[] {"dev", "test"}) {
            MockEnvironment local = new MockEnvironment();
            local.setActiveProfiles(development, "demo");
            assertThat(ProductionStartGuard.problems(local)).as(development).isEmpty();
        }
    }

    @Test
    @DisplayName("Under dev, test and migrate every development setting is allowed")
    void relaxedProfilesAllowEverything() {
        for (String profile : new String[] {"dev", "test", "migrate"}) {
            MockEnvironment environment = new MockEnvironment()
                    .withProperty("smc.providers.mail", "console_mail")
                    .withProperty("smc.entities.schema-gate-enabled", "false");
            environment.setActiveProfiles(profile);
            assertThat(ProductionStartGuard.problems(environment)).as(profile).isEmpty();
        }
    }

    @Test
    @DisplayName("A real start without a profile and without SMC_PUBLIC_URL fails before the context is built")
    void realStartIsRefused() {
        SpringApplication application = new SpringApplication(Empty.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setRegisterShutdownHook(false);

        assertThatThrownBy(() -> application.run(
                        "--smc.typesense.api-key=a-key-of-this-installation",
                        "--DB_PASSWORD=a-password-of-this-installation",
                        "--smc.providers.mail=smtp"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SMC_PUBLIC_URL");
    }

    @Configuration(proxyBeanMethods = false)
    static class Empty {}
}
