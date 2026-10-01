package com.smartup24.cms.instance.config.env;

import com.smartup24.cms.instance.common.env.LegacyConfigNames;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.commons.logging.Log;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * Reads the configuration names used before ADR-0027 as aliases of the current ones (plan 10/10, item 4.1).
 *
 * <p>For every property source holding an old name ({@link LegacyConfigNames}) whose current name the same source
 * lacks, an alias source with the current name is placed right after it. The alias therefore has the precedence of
 * the setting it came from: an old command-line argument still beats application.yml, and a current name set
 * anywhere with higher precedence still wins. Environment variables get an alias source of the environment kind, so
 * {@code SMC_TYPESENSE_API_KEY} resolves both as a placeholder and through relaxed binding of
 * {@code smc.typesense.api-key}. Each old name in use is logged once as a warning; values are never logged.
 */
public class LegacyConfigAliases implements EnvironmentPostProcessor, Ordered {

    /** After the configuration files are loaded, so their old keys are seen too. */
    public static final int ORDER = ConfigDataEnvironmentPostProcessor.ORDER + 1;

    /** Prefix of the alias sources this processor adds. */
    static final String SOURCE_PREFIX = "legacyConfigNames:";

    /** Spring Boot binds an environment-kind source with relaxed names only under a name with this suffix. */
    private static final String ENVIRONMENT_SUFFIX = "-systemEnvironment";

    private final Log log;

    public LegacyConfigAliases(DeferredLogFactory logFactory) {
        this(logFactory.getLog(LegacyConfigAliases.class));
    }

    LegacyConfigAliases(Log log) {
        this.log = log;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        MutablePropertySources sources = environment.getPropertySources();
        Set<String> reported = new HashSet<>();
        for (PropertySource<?> source : sources.stream().toList()) {
            if (source instanceof EnumerablePropertySource<?> enumerable
                    && !source.getName().startsWith(SOURCE_PREFIX)) {
                aliasSource(enumerable, reported).ifPresent(alias -> sources.addAfter(source.getName(), alias));
            }
        }
    }

    private Optional<PropertySource<?>> aliasSource(EnumerablePropertySource<?> source, Set<String> reported) {
        boolean environmentKind = source instanceof SystemEnvironmentPropertySource;
        Map<String, Object> aliases = new LinkedHashMap<>();
        for (String name : source.getPropertyNames()) {
            Optional<String> current =
                    environmentKind ? LegacyConfigNames.environmentVariable(name) : LegacyConfigNames.property(name);
            Object value = source.getProperty(name);
            if (current.isEmpty() || value == null || source.containsProperty(current.get())) {
                continue;
            }
            aliases.put(current.get(), value);
            if (reported.add(name)) {
                log.warn(LegacyConfigNames.warning(name, current.get()));
            }
        }
        if (aliases.isEmpty()) {
            return Optional.empty();
        }
        String name = SOURCE_PREFIX + source.getName();
        return Optional.of(
                environmentKind
                        ? new SystemEnvironmentPropertySource(name + ENVIRONMENT_SUFFIX, aliases)
                        : new MapPropertySource(name, aliases));
    }
}
