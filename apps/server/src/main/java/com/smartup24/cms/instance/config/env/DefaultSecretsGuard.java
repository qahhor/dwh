package com.smartup24.cms.instance.config.env;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;
import org.springframework.core.env.PropertySourcesPropertyResolver;

/**
 * Refuses to start outside development while a secret keeps a value published in this repository (plan 10/10,
 * item 4.1; ADR-0027).
 *
 * <p>The defaults of application.yml, application-dev.yml, docker-compose.yml and .env.example let a developer start
 * the stack without preparing secrets; anyone who reads the repository knows them. Under the {@code dev} and
 * {@code test} profiles they are fine, and the {@code migrate} run uses none of them (it builds the schema and
 * exits). Any other start (production runs without a profile) fails before the context is built, and the error names
 * the variables to set; the values are never printed.
 */
public class DefaultSecretsGuard implements EnvironmentPostProcessor, Ordered {

    /** After the configuration files are loaded, so a secret set in any of them is checked too. */
    public static final int ORDER = ConfigDataEnvironmentPostProcessor.ORDER + 1;

    /** Profiles under which the published defaults are allowed. */
    static final Profiles RELAXED = Profiles.of("dev", "test", "migrate");

    /** The secrets with a published default, the variables that set them, and when a secret is in use at all. */
    static final List<Secret> SECRETS = List.of(
            new Secret(
                    "smc.typesense.api-key",
                    "SMC_TYPESENSE_API_KEY",
                    "TYPESENSE_API_KEY",
                    Set.of("", "smartupcms_typesense_local_dev"),
                    "smc.typesense.enabled"),
            new Secret(
                    "smc.instance.admin-password",
                    "SMC_INSTANCE_ADMIN_PASSWORD",
                    "ADMIN_PASSWORD",
                    Set.of("DevOnly-ChangeMe-1"),
                    null),
            new Secret(
                    "spring.datasource.password",
                    "DB_PASSWORD",
                    null,
                    Set.of("postgres", "smartupcms_local_dev"),
                    null),
            new Secret(
                    "warehouse.password",
                    "WAREHOUSE_PASSWORD",
                    "DB_PASSWORD",
                    Set.of("postgres", "smartupcms_local_dev"),
                    null));

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.acceptsProfiles(RELAXED)) {
            return;
        }
        // A placeholder without a value (a required variable not set) is not a default: the binding reports it later.
        PropertySourcesPropertyResolver resolver =
                new PropertySourcesPropertyResolver(environment.getPropertySources());
        resolver.setIgnoreUnresolvableNestedPlaceholders(true);
        String defaulted = SECRETS.stream()
                .filter(secret -> secret.keepsDefault(resolver))
                .map(Secret::describe)
                .collect(Collectors.joining(", "));
        if (!defaulted.isEmpty()) {
            throw new IllegalStateException("Refusing to start: a secret keeps its published development default"
                    + " outside the dev and test profiles (ADR-0027). Set a value of your own for: " + defaulted);
        }
    }

    /**
     * One secret with a published default.
     *
     * @param property  the property the application reads
     * @param variable        the environment variable the server reads
     * @param composeVariable the variable of the operator's .env that Compose passes on, or null when the same
     * @param defaults        the published values, the empty string standing for "not set" where the code falls back
     * @param enabledBy       the boolean property that turns the feature using the secret on, or null when always used
     */
    record Secret(
            String property,
            String variable,
            @Nullable String composeVariable,
            Set<String> defaults,
            @Nullable String enabledBy) {

        String describe() {
            return composeVariable == null ? variable : variable + " (" + composeVariable + " in Compose)";
        }

        boolean keepsDefault(PropertySourcesPropertyResolver resolver) {
            if (enabledBy != null && !resolver.getProperty(enabledBy, Boolean.class, true)) {
                return false;
            }
            String value = resolver.getProperty(property);
            return value != null && defaults.contains(value.trim());
        }
    }
}
