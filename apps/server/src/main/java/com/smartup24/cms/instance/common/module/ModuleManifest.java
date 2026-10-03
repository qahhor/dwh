package com.smartup24.cms.instance.common.module;

import com.smartup24.cms.platform.api.PlatformVersion;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * What a module says about itself in {@code META-INF/smartupcms/modules/<code>.json} (ADR-0033, 6.2; plan 10/10, item
 * 6.4): its code in the registry, name, version, the least version of the platform's API it runs on, the modules it
 * needs, and — for a module outside the monorepo — the configuration the platform imports, its migrations and its
 * messages.
 *
 * @param code          the module's code in {@code md_installed_modules}
 * @param name          its name in the registry
 * @param version       its version
 * @param minPlatform   the least version of the platform's API it runs on
 * @param dependencies  the modules it needs, with their least versions
 * @param configuration the {@code @Configuration} class the platform imports, or null for a built-in module
 * @param migrations    the classpath location of its Flyway migrations, or null
 * @param messages      the classpath folder of its {@code ru.json}, {@code uz.json} and {@code en.json}, or null
 * @param source        where the manifest was read from, for the messages of a refusal
 */
public record ModuleManifest(
        String code,
        String name,
        PlatformVersion version,
        PlatformVersion minPlatform,
        List<Dependency> dependencies,
        @Nullable String configuration,
        @Nullable String migrations,
        @Nullable String messages,
        String source) {

    /** The rule of a module code: what the registry and the Flyway history table of the module accept. */
    public static final Pattern CODE = Pattern.compile("^[a-z][a-z0-9_]{1,31}$");

    private static final Pattern CLASS_NAME = Pattern.compile("^[a-zA-Z_$][\\w$]*(\\.[a-zA-Z_$][\\w$]*)+$");
    private static final Pattern LOCATION = Pattern.compile("^[a-zA-Z0-9_.-]+(/[a-zA-Z0-9_.-]+)*$");

    /** A module this one needs, and the least version of it. */
    public record Dependency(String code, PlatformVersion version) {
        public Dependency {
            requireCode(code);
            Objects.requireNonNull(version, "version");
        }
    }

    public ModuleManifest {
        requireCode(code);
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Module " + code + " has no name");
        }
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(minPlatform, "minPlatform");
        dependencies = List.copyOf(dependencies);
        if (dependencies.stream().anyMatch(dependency -> dependency.code().equals(code))) {
            throw new IllegalArgumentException("Module " + code + " depends on itself");
        }
        if (dependencies.stream().map(Dependency::code).distinct().count() != dependencies.size()) {
            throw new IllegalArgumentException("Module " + code + " names a dependency twice");
        }
        if (configuration != null && !CLASS_NAME.matcher(configuration).matches()) {
            throw new IllegalArgumentException("Module " + code + ": bad configuration class " + configuration);
        }
        requireLocation(code, "migrations", migrations);
        requireLocation(code, "messages", messages);
        Objects.requireNonNull(source, "source");
    }

    /** The table of the module's own Flyway history (ADR-0033, 6.5). */
    public String historyTable() {
        return "flyway_module_" + code;
    }

    private static void requireCode(String code) {
        if (code == null || !CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("Bad module code: " + code);
        }
    }

    private static void requireLocation(String code, String what, @Nullable String location) {
        if (location != null && !LOCATION.matcher(location).matches()) {
            throw new IllegalArgumentException("Module " + code + ": bad " + what + " location " + location);
        }
    }
}
