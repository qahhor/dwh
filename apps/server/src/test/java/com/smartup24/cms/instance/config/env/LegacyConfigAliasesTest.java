package com.smartup24.cms.instance.config.env;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.commons.logging.Log;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/** Old configuration names keep working as aliases until the sunset, with one warning each (ADR-0027). */
class LegacyConfigAliasesTest {

    private final List<String> warnings = new ArrayList<>();
    private final LegacyConfigAliases aliases = new LegacyConfigAliases(capturingLog(warnings));

    @Test
    @DisplayName("4.1: правила имён — продукт в smc, хранилище в warehouse, особые имена переменных отдельно")
    void namesFollowTheRules() {
        assertThat(LegacyConfigNames.environmentVariable("DWH_TYPESENSE_API_KEY"))
                .hasValue("SMC_TYPESENSE_API_KEY");
        assertThat(LegacyConfigNames.environmentVariable("APP_DWH_URL")).hasValue("WAREHOUSE_URL");
        assertThat(LegacyConfigNames.environmentVariable("DWH_DATA_DB_USER")).hasValue("WAREHOUSE_USERNAME");
        assertThat(LegacyConfigNames.environmentVariable("DWH_DB_NAME")).hasValue("WAREHOUSE_DB_NAME");
        assertThat(LegacyConfigNames.environmentVariable("DWH_DB_URL")).hasValue("DB_URL");
        assertThat(LegacyConfigNames.environmentVariable("DWH_FND_JOBS_TICK")).hasValue("SMC_JOBS_TICK");
        assertThat(LegacyConfigNames.environmentVariable("DWH_JOBS_LEASE")).hasValue("SMC_JOBS_LEASE");
        assertThat(LegacyConfigNames.environmentVariable("SMC_TYPESENSE_API_KEY"))
                .isEmpty();
        assertThat(LegacyConfigNames.environmentVariable("DWH_")).isEmpty();
        assertThat(LegacyConfigNames.property("dwh.typesense.url")).hasValue("smc.typesense.url");
        assertThat(LegacyConfigNames.property("dwh.fnd.jobs.lease")).hasValue("smc.jobs.lease");
        assertThat(LegacyConfigNames.property("app.dwh.connect-timeout")).hasValue("warehouse.connect-timeout");
        assertThat(LegacyConfigNames.property("warehouse.url")).isEmpty();
    }

    @Test
    @DisplayName("4.1: старая переменная окружения заполняет плейсхолдер и relaxed-привязку нового имени")
    void oldEnvironmentVariableFeedsTheNewName() {
        StandardEnvironment environment = environment(
                Map.of("DWH_TYPESENSE_API_KEY", "key-from-old-name", "DWH_SESSION_CLEANUP_INTERVAL", "2h"),
                Map.of("smc.typesense.api-key", "${SMC_TYPESENSE_API_KEY:dev-default}"));

        aliases.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("smc.typesense.api-key")).isEqualTo("key-from-old-name");
        assertThat(environment.getProperty("smc.session.cleanup-interval")).isEqualTo("2h");
        ConfigurationPropertySources.attach(environment);
        assertThat(Binder.get(environment)
                        .bind("smc.session.cleanup-interval", String.class)
                        .get())
                .isEqualTo("2h");
        assertThat(warnings)
                .hasSize(2)
                .anySatisfy(warning -> assertThat(warning)
                        .contains("DWH_TYPESENSE_API_KEY", "SMC_TYPESENSE_API_KEY", "2026-12-31", "ADR-0027"))
                .noneSatisfy(warning -> assertThat(warning).contains("key-from-old-name"));
    }

    @Test
    @DisplayName("4.1: если задано и новое имя, читается новое и предупреждения нет")
    void currentNameWins() {
        StandardEnvironment environment = environment(
                Map.of("DWH_WEBHOOKS_ENABLED", "true", "SMC_WEBHOOKS_ENABLED", "false"),
                Map.of("smc.webhooks.enabled", "${SMC_WEBHOOKS_ENABLED:false}"));

        aliases.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("smc.webhooks.enabled")).isEqualTo("false");
        assertThat(warnings).isEmpty();
    }

    @Test
    @DisplayName("4.1: старый ключ сохраняет приоритет своего источника: аргумент командной строки сильнее yml")
    void oldKeyKeepsThePrecedenceOfItsSource() {
        StandardEnvironment environment = environment(Map.of(), Map.of("smc.typesense.url", "http://typesense:8108"));
        environment
                .getPropertySources()
                .addFirst(new MapPropertySource(
                        "commandLineArgs",
                        Map.of("dwh.typesense.url", "http://search:8108", "app.dwh.url", "jdbc:postgresql://db/w")));

        aliases.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("smc.typesense.url")).isEqualTo("http://search:8108");
        assertThat(environment.getProperty("warehouse.url")).isEqualTo("jdbc:postgresql://db/w");
        assertThat(warnings).hasSize(2);
    }

    @Test
    @DisplayName("4.1: одно и то же старое имя в двух источниках даёт одно предупреждение")
    void oneWarningPerName() {
        StandardEnvironment environment = environment(Map.of(), Map.of("dwh.mail.from", "a@example.com"));
        environment
                .getPropertySources()
                .addFirst(new MapPropertySource("commandLineArgs", Map.of("dwh.mail.from", "b@example.com")));

        aliases.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("smc.mail.from")).isEqualTo("b@example.com");
        assertThat(warnings).hasSize(1);
    }

    private static StandardEnvironment environment(Map<String, Object> variables, Map<String, Object> configuration) {
        StandardEnvironment environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .replace(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        new SystemEnvironmentPropertySource(
                                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables));
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addLast(new MapPropertySource("application.yml", configuration));
        return environment;
    }

    /** A log that keeps the warnings and ignores everything else. */
    private static Log capturingLog(List<String> warnings) {
        return (Log) Proxy.newProxyInstance(
                Log.class.getClassLoader(), new Class<?>[] {Log.class}, (proxy, method, args) -> {
                    if (method.getName().equals("warn") && args != null && args.length > 0) {
                        warnings.add(String.valueOf(args[0]));
                    }
                    return method.getReturnType() == boolean.class ? Boolean.TRUE : null;
                });
    }
}
