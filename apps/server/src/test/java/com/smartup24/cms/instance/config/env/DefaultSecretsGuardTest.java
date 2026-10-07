package com.smartup24.cms.instance.config.env;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.env.MockEnvironment;

/** Outside the dev and test profiles the published development secrets stop the start (ADR-0027). */
class DefaultSecretsGuardTest {

    private static final String DEV_TYPESENSE_KEY = "smartupcms_typesense_local_dev";

    private final DefaultSecretsGuard guard = new DefaultSecretsGuard();

    @Test
    @DisplayName("4.1: без профиля ключ Typesense по умолчанию останавливает старт, ошибка называет переменную")
    void defaultTypesenseKeyStopsTheStart() {
        MockEnvironment environment = new MockEnvironment().withProperty("smc.typesense.api-key", DEV_TYPESENSE_KEY);

        assertThatThrownBy(() -> guard.postProcessEnvironment(environment, new SpringApplication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SMC_TYPESENSE_API_KEY")
                .hasMessageContaining("TYPESENSE_API_KEY in Compose")
                .hasMessageNotContaining(DEV_TYPESENSE_KEY);
    }

    @Test
    @DisplayName("4.1: все секреты по умолчанию перечислены в одной ошибке")
    void everyDefaultedSecretIsNamed() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("smc.typesense.api-key", "")
                .withProperty("smc.instance.admin-password", "DevOnly-ChangeMe-1")
                .withProperty("spring.datasource.password", "postgres")
                .withProperty("warehouse.password", "smartupcms_local_dev");

        assertThatThrownBy(() -> guard.postProcessEnvironment(environment, new SpringApplication()))
                .hasMessageContaining("SMC_TYPESENSE_API_KEY")
                .hasMessageContaining("SMC_INSTANCE_ADMIN_PASSWORD")
                .hasMessageContaining("DB_PASSWORD")
                .hasMessageContaining("WAREHOUSE_PASSWORD")
                .hasMessageNotContaining("DevOnly-ChangeMe-1");
    }

    @Test
    @DisplayName("4.1: в профилях dev, test и migrate значения разработки допустимы")
    void relaxedProfilesAllowTheDefaults() {
        for (String profile : new String[] {"dev", "test", "migrate"}) {
            MockEnvironment environment =
                    new MockEnvironment().withProperty("smc.typesense.api-key", DEV_TYPESENSE_KEY);
            environment.setActiveProfiles(profile);
            assertThatCode(() -> guard.postProcessEnvironment(environment, new SpringApplication()))
                    .as(profile)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("4.1: свои значения, выключенный Typesense и незаданная переменная старт не останавливают")
    void ownValuesPass() {
        MockEnvironment own = new MockEnvironment()
                .withProperty("smc.typesense.api-key", "a-key-of-this-installation")
                .withProperty("smc.instance.admin-password", "")
                .withProperty("spring.datasource.password", "a-password-of-this-installation")
                .withProperty("warehouse.password", "${WAREHOUSE_PASSWORD}");
        assertThatCode(() -> guard.postProcessEnvironment(own, new SpringApplication()))
                .doesNotThrowAnyException();

        MockEnvironment searchOff = new MockEnvironment()
                .withProperty("smc.typesense.enabled", "false")
                .withProperty("smc.typesense.api-key", DEV_TYPESENSE_KEY);
        assertThatCode(() -> guard.postProcessEnvironment(searchOff, new SpringApplication()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("4.1: настоящий старт без профиля с ключом Typesense из application.yml падает с понятной ошибкой")
    void productionStartWithTheDefaultKeyFails() {
        SpringApplication application = new SpringApplication(Empty.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setRegisterShutdownHook(false);

        assertThatThrownBy(() -> application.run("--DB_PASSWORD=a-password-of-this-installation"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refusing to start")
                .hasMessageContaining("SMC_TYPESENSE_API_KEY")
                .hasMessageNotContaining(" DB_PASSWORD");
    }

    @Test
    @DisplayName("4.1: старый ключ dwh.typesense.api-key не читается: остаётся ключ по умолчанию, старт падает")
    void oldKeyIsIgnored() {
        SpringApplication application = new SpringApplication(Empty.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setRegisterShutdownHook(false);

        assertThatThrownBy(() -> application.run(
                        "--dwh.typesense.api-key=a-key-of-this-installation",
                        "--DB_PASSWORD=a-password-of-this-installation"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SMC_TYPESENSE_API_KEY");
    }

    @Test
    @DisplayName("4.1: ключ smc.typesense.api-key своей установки доходит до приложения")
    void currentKeyReachesTheApplication() {
        SpringApplication application = new SpringApplication(Empty.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setRegisterShutdownHook(false);

        // The settings ProductionStartGuard asks for outside development: this start is a production one.
        try (ConfigurableApplicationContext context = application.run(
                "--smc.typesense.api-key=a-key-of-this-installation",
                "--DB_PASSWORD=a-password-of-this-installation",
                "--smc.public-url=https://cms.example.test",
                "--smc.providers.mail=smtp")) {
            assertThat(context.getEnvironment().getProperty("smc.typesense.api-key"))
                    .isEqualTo("a-key-of-this-installation");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Empty {}
}
