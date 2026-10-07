package com.smartup24.cms.instance.common.module;

import com.smartup24.cms.platform.api.PlatformVersion;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads and checks the manifests of the modules on the classpath (ADR-0033, 6.2–6.3; plan 10/10, item 6.4). The check
 * runs before any bean is created ({@code config.module.ModuleManifestSelector}): a module that needs a newer platform,
 * a missing module or a newer version of one stops the start with a message that names it.
 */
public final class ModuleManifests {

    /** The folder of the manifests in a module's jar; a manifest is named after its module's code. */
    public static final String LOCATION = "META-INF/smartupcms/modules/";

    private static final String PATTERN = "classpath*:" + LOCATION + "*.json";
    private static final Set<String> FIELDS = Set.of(
            "code",
            "name",
            "version",
            "minPlatform",
            "dependencies",
            "areas",
            "configuration",
            "migrations",
            "messages");
    private static final Set<String> DEPENDENCY_FIELDS = Set.of("code", "version");
    private static final JsonMapper JSON = JsonMapper.shared();

    private ModuleManifests() {}

    /** Every manifest on the class loader's classpath, by code; a malformed one refuses (ADR-0033, 6.2). */
    public static List<ModuleManifest> read(ClassLoader loader) {
        Resource[] resources;
        try {
            resources = new PathMatchingResourcePatternResolver(loader).getResources(PATTERN);
        } catch (IOException e) {
            throw new ModuleManifestException("The module manifests on the classpath are unreadable", e);
        }
        List<ModuleManifest> manifests = new ArrayList<>();
        for (Resource resource : resources) {
            String source = resource.getDescription();
            try (InputStream in = resource.getInputStream()) {
                manifests.add(parse(source, String.valueOf(resource.getFilename()), in));
            } catch (IOException e) {
                throw new ModuleManifestException("The module manifest " + source + " is unreadable", e);
            }
        }
        manifests.sort(Comparator.comparing(ModuleManifest::code));
        return List.copyOf(manifests);
    }

    /** The manifests of the classpath, checked against the platform; a refusal names every problem. */
    public static List<ModuleManifest> checked(ClassLoader loader, PlatformVersion platform) {
        List<ModuleManifest> manifests = read(loader);
        List<String> problems = problems(manifests, platform);
        if (!problems.isEmpty()) {
            throw new ModuleManifestException("The modules cannot start on platform API " + platform + ":\n  - "
                    + String.join("\n  - ", problems));
        }
        return inDependencyOrder(manifests);
    }

    /**
     * Reads one manifest. The file is named after the module's code; a field the schema does not know refuses, so a
     * typo cannot switch a check off.
     */
    static ModuleManifest parse(String source, String fileName, InputStream in) {
        try {
            JsonNode root = JSON.readTree(in);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("a manifest is a JSON object");
            }
            requireKnown(root, FIELDS, "");
            String code = required(root, "code");
            if (!fileName.equals(code + ".json")) {
                throw new IllegalArgumentException("the file of module " + code + " is named " + code + ".json");
            }
            List<ModuleManifest.Dependency> dependencies = new ArrayList<>();
            JsonNode listed = root.get("dependencies");
            if (listed != null && !listed.isNull()) {
                if (!listed.isArray()) throw new IllegalArgumentException("dependencies is an array");
                for (JsonNode dependency : listed) {
                    if (!dependency.isObject()) throw new IllegalArgumentException("a dependency is an object");
                    requireKnown(dependency, DEPENDENCY_FIELDS, "dependencies.");
                    dependencies.add(new ModuleManifest.Dependency(
                            required(dependency, "code"), PlatformVersion.parse(required(dependency, "version"))));
                }
            }
            return new ModuleManifest(
                    code,
                    required(root, "name"),
                    PlatformVersion.parse(required(root, "version")),
                    PlatformVersion.parse(required(root, "minPlatform")),
                    dependencies,
                    strings(root, "areas"),
                    optional(root, "configuration"),
                    optional(root, "migrations"),
                    optional(root, "messages"),
                    source);
        } catch (JacksonException | IllegalArgumentException e) {
            throw new ModuleManifestException("The module manifest " + source + " is invalid: " + e.getMessage(), e);
        }
    }

    /**
     * What keeps the modules from starting on the platform (ADR-0033, 6.3): a code declared twice, a platform of
     * another major version or older than a module's {@code minPlatform}, a dependency that is missing, of another
     * major version or older than required, and a cycle of dependencies.
     */
    public static List<String> problems(List<ModuleManifest> manifests, PlatformVersion platform) {
        List<String> problems = new ArrayList<>();
        Map<String, ModuleManifest> byCode = new LinkedHashMap<>();
        for (ModuleManifest manifest : manifests) {
            ModuleManifest twin = byCode.putIfAbsent(manifest.code(), manifest);
            if (twin != null) {
                problems.add("module " + manifest.code() + " is declared twice: " + twin.source() + " and "
                        + manifest.source());
            }
        }
        Map<String, String> areas = new LinkedHashMap<>();
        byCode.keySet().forEach(code -> areas.put(code, code));
        for (ModuleManifest manifest : byCode.values()) {
            for (String area : manifest.areas()) {
                String holder = areas.putIfAbsent(area, manifest.code());
                if (holder != null) {
                    problems.add("permission area " + area + " of module " + manifest.code() + " is held by module "
                            + holder);
                }
            }
        }
        for (ModuleManifest manifest : byCode.values()) {
            String module = "module " + manifest.code() + " " + manifest.version();
            if (!platform.satisfies(manifest.minPlatform())) {
                problems.add(module + " needs platform API >= " + manifest.minPlatform() + " (major "
                        + manifest.minPlatform().major() + "); this platform provides " + platform);
            }
            for (ModuleManifest.Dependency dependency : manifest.dependencies()) {
                ModuleManifest installed = byCode.get(dependency.code());
                if (installed == null) {
                    problems.add(module + " needs module " + dependency.code() + " >= " + dependency.version()
                            + ", which is not installed");
                } else if (!installed.version().satisfies(dependency.version())) {
                    problems.add(module + " needs module " + dependency.code() + " >= " + dependency.version()
                            + " (major " + dependency.version().major() + "); installed is " + installed.version());
                }
            }
        }
        if (problems.isEmpty()) {
            String cycle = cycle(byCode);
            if (cycle != null) problems.add("modules depend on each other in a cycle: " + cycle);
        }
        return problems;
    }

    /** The manifests with every module after the modules it needs, otherwise by code. */
    public static List<ModuleManifest> inDependencyOrder(List<ModuleManifest> manifests) {
        Map<String, ModuleManifest> byCode = new LinkedHashMap<>();
        manifests.stream()
                .sorted(Comparator.comparing(ModuleManifest::code))
                .forEach(manifest -> byCode.putIfAbsent(manifest.code(), manifest));
        Set<String> placed = new LinkedHashSet<>();
        for (String code : byCode.keySet()) {
            place(code, byCode, placed, new LinkedHashSet<>());
        }
        return placed.stream().map(byCode::get).toList();
    }

    private static void place(
            String code, Map<String, ModuleManifest> byCode, Set<String> placed, Set<String> visiting) {
        ModuleManifest manifest = byCode.get(code);
        if (manifest == null || placed.contains(code) || !visiting.add(code)) return;
        new TreeSet<>(manifest.dependencies().stream()
                        .map(ModuleManifest.Dependency::code)
                        .toList())
                .forEach(dependency -> place(dependency, byCode, placed, visiting));
        placed.add(code);
    }

    private static @Nullable String cycle(Map<String, ModuleManifest> byCode) {
        for (String start : byCode.keySet()) {
            List<String> path = new ArrayList<>();
            if (reaches(start, start, byCode, path, new LinkedHashSet<>())) {
                return start + " -> " + String.join(" -> ", path);
            }
        }
        return null;
    }

    private static boolean reaches(
            String target, String from, Map<String, ModuleManifest> byCode, List<String> path, Set<String> seen) {
        ModuleManifest manifest = byCode.get(from);
        if (manifest == null || !seen.add(from)) return false;
        for (ModuleManifest.Dependency dependency : manifest.dependencies()) {
            path.add(dependency.code());
            if (dependency.code().equals(target) || reaches(target, dependency.code(), byCode, path, seen)) {
                return true;
            }
            path.removeLast();
        }
        return false;
    }

    /** An optional array of strings; absent means none. */
    private static List<String> strings(JsonNode node, String name) {
        JsonNode listed = node.get(name);
        if (listed == null || listed.isNull()) return List.of();
        if (!listed.isArray()) throw new IllegalArgumentException(name + " is an array");
        List<String> values = new ArrayList<>();
        for (JsonNode value : listed) {
            if (!value.isString()) throw new IllegalArgumentException(name + " holds strings");
            values.add(value.asString());
        }
        return values;
    }

    private static void requireKnown(JsonNode node, Set<String> known, String prefix) {
        for (String name : node.propertyNames()) {
            if (!known.contains(name)) {
                throw new IllegalArgumentException("unknown field " + prefix + name);
            }
        }
    }

    private static String required(JsonNode node, String name) {
        String value = optional(node, name);
        if (value == null) throw new IllegalArgumentException("no " + name);
        return value;
    }

    private static @Nullable String optional(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) return null;
        if (!value.isString()) {
            throw new IllegalArgumentException(name + " is a string");
        }
        return value.asString();
    }
}
