package com.smartup24.cms.instance.config.env;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;
import org.springframework.core.env.PropertySourcesPropertyResolver;

/**
 * Refuses to start outside development with a setting that only development may use (ADR-0027, like
 * {@link DefaultSecretsGuard}, after it):
 *
 * <ul>
 *   <li>the {@code demo} profile without {@code dev} or {@code test}: it fills the installation with demo users and
 *       records;
 *   <li>no public address ({@code SMC_PUBLIC_URL}): invitation and password reset links are built from it, and
 *       without it they are never sent;
 *   <li>a schema gate switched off ({@code smc.entities.schema-gate-enabled}, {@code smc.schema-gate.enabled}): only
 *       {@code cms migration diff} starts on a schema that differs from the declarations, and it runs as a test;
 *   <li>the stub mail provider ({@code console_mail}) while delivery is enforced ({@code SMC_DELIVERY_ENFORCE}, on by
 *       default): invitations and reset links would go nowhere; {@code SMC_DELIVERY_ENFORCE=false} accepts that
 *       knowingly.
 * </ul>
 *
 * <p>Under {@code dev} and {@code test} these are allowed; the {@code migrate} run uses none of them except the demo
 * profile. The error names every setting to change and no secret.
 */
public class ProductionStartGuard implements EnvironmentPostProcessor, Ordered {

    /** Right after {@link DefaultSecretsGuard}: a published secret is reported first. */
    public static final int ORDER = DefaultSecretsGuard.ORDER + 1;

    static final Profiles DEVELOPMENT = Profiles.of("dev", "test");

    static final Profiles RELAXED = Profiles.of("dev", "test", "migrate");

    static final String STUB_PREFIX = "console_";

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        List<String> problems = problems(environment);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Refusing to start: a development setting outside the dev and test"
                    + " profiles (docs/ops/configuration-reference.md):\n  - " + String.join("\n  - ", problems));
        }
    }

    /** What keeps this environment from starting; empty when it may. */
    static List<String> problems(ConfigurableEnvironment environment) {
        List<String> problems = new ArrayList<>();
        if (environment.acceptsProfiles(Profiles.of("demo")) && !environment.acceptsProfiles(DEVELOPMENT)) {
            problems.add("the demo profile runs only together with dev or test");
        }
        if (environment.acceptsProfiles(RELAXED)) {
            return problems;
        }
        PropertySourcesPropertyResolver resolver =
                new PropertySourcesPropertyResolver(environment.getPropertySources());
        resolver.setIgnoreUnresolvableNestedPlaceholders(true);
        String publicUrl = resolver.getProperty("smc.public-url", "").strip();
        if (publicUrl.isEmpty()) {
            problems.add("SMC_PUBLIC_URL is not set: invitation and password reset links are built from it");
        }
        if (!resolver.getProperty("smc.entities.schema-gate-enabled", Boolean.class, true)) {
            problems.add("SMC_ENTITIES_SCHEMA_GATE_ENABLED=false is only for cms migration diff");
        }
        if (!resolver.getProperty("smc.schema-gate.enabled", Boolean.class, true)) {
            problems.add("SMC_SCHEMA_GATE_ENABLED=false: the schema must match the migrations");
        }
        String mail = resolver.getProperty("smc.providers.mail", "console_mail")
                .strip()
                .toLowerCase(Locale.ROOT);
        if (mail.startsWith(STUB_PREFIX) && resolver.getProperty("smc.delivery.enforce", Boolean.class, true)) {
            problems.add("SMC_PROVIDER_MAIL=" + mail + " delivers nothing: configure SMTP (SMC_PROVIDER_MAIL=smtp,"
                    + " SMTP_HOST) or set SMC_DELIVERY_ENFORCE=false knowingly");
        }
        return problems;
    }
}
