package com.smartup24.cms.instance.architecture;

import static com.smartup24.cms.instance.architecture.ModuleBoundariesTest.INFRASTRUCTURE;
import static com.smartup24.cms.instance.architecture.ModuleBoundariesTest.MODULES;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plan 10/10, item 1.3: the module rules of {@link ModuleBoundariesTest}, read from the imports of the sources.
 *
 * <p>The bytecode rules miss a compile-time constant: {@code MdPref.STATE_ACTIVE} is inlined into its user, so the
 * class file of {@code KauthAuthService} no longer names {@code MdPref}. The import still does. This check reads every
 * import of the main sources, the static ones included, and holds them to the same rules: another module only
 * through its {@code service} or {@code api} package, the wiring ({@code config}) from no module, no business module
 * from {@code common} or an infrastructure module. A type named by its full name without an import is not seen; the
 * code base names none, and the bytecode rules still see every use that is not a constant.
 */
class ModuleSourceBoundariesTest {

    private static final Path SOURCES = Path.of("src/main/java/com/smartup24/cms/instance");
    private static final String CONFIG = "config";
    private static final String COMMON = "common";
    /** An import of a type of the application, or of all of a package's types; the static form included. */
    private static final Pattern IMPORT = Pattern.compile(
            "(?m)^import\\s+(?:static\\s+)?com\\.smartup24\\.cms\\.instance\\.([a-z0-9_.]+?)\\.(?:[A-Z]\\w*|\\*)");

    @Test
    @DisplayName("1.3: the imports of the sources keep the module rules, constants included")
    void importsKeepTheModuleRules() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            files.filter(file -> file.toString().endsWith(".java")).forEach(file -> {
                String relative = SOURCES.relativize(file).toString().replace('\\', '/');
                found.addAll(violations(relative, read(file)));
            });
        }
        assertThat(found)
                .as("import another module from its api or service package; publish a shared constant in the"
                        + " owner's api package")
                .isEmpty();
    }

    @Test
    @DisplayName("1.3: the import check still catches what it forbids")
    void importCheckCatchesViolations() {
        assertThat(violations(
                        "kauth/service/Probe.java", "import com.smartup24.cms.instance.md.pref.PermissionAreas;\n"))
                .containsExactly("kauth/service/Probe.java: kauth -> md.pref.PermissionAreas");
        assertThat(violations(
                        "kauth/service/Probe.java",
                        "import static com.smartup24.cms.instance.md.repository.MdUserRepository.TABLE;\n"))
                .containsExactly("kauth/service/Probe.java: kauth -> md.repository.MdUserRepository");
        assertThat(violations("kauth/service/Probe.java", "import com.smartup24.cms.instance.md.repository.*;\n"))
                .containsExactly("kauth/service/Probe.java: kauth -> md.repository.*");
        assertThat(violations("config/Probe.java", "import com.smartup24.cms.instance.kauth.security.Filter;\n"))
                .containsExactly("config/Probe.java: config -> kauth.security.Filter");
        assertThat(violations("md/service/Probe.java", "import com.smartup24.cms.instance.config.db.Gate;\n"))
                .containsExactly("md/service/Probe.java: md -> config.db.Gate (no module depends on the wiring)");
        assertThat(violations("jobs/config/Probe.java", "import com.smartup24.cms.instance.md.service.Settings;\n"))
                .containsExactly("jobs/config/Probe.java: jobs -> md.service.Settings (infrastructure)");
        assertThat(violations("common/json/Probe.java", "import com.smartup24.cms.instance.md.api.MdPref;\n"))
                .containsExactly("common/json/Probe.java: common -> md.api.MdPref (infrastructure)");
        assertThat(violations(
                        "kauth/service/Probe.java",
                        "import com.smartup24.cms.instance.md.api.MdPref;\n"
                                + "import com.smartup24.cms.instance.md.service.MdUserService;\n"
                                + "import com.smartup24.cms.instance.kauth.pref.KauthSessionProperties;\n"
                                + "import com.smartup24.cms.instance.common.json.JsonColumns;\n"))
                .isEmpty();
        assertThat(violations("ms/task/service/Probe.java", "import com.smartup24.cms.instance.ms.notify.api.X;\n"))
                .isEmpty();
        assertThat(violations("ms/task/service/Probe.java", "import com.smartup24.cms.instance.ms.notify.sse.X;\n"))
                .containsExactly("ms/task/service/Probe.java: ms.task -> ms.notify.sse.X");
    }

    /** The violations of one source, each as {@code <file>: <from> -> <imported package>.<type>}. */
    static List<String> violations(String relative, String source) {
        Optional<String> from = moduleOfPackage(
                relative.substring(0, Math.max(0, relative.lastIndexOf('/'))).replace('/', '.'));
        if (from.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        Matcher matcher = IMPORT.matcher(source);
        while (matcher.find()) {
            String pkg = matcher.group(1);
            String imported = source.substring(matcher.start(1), matcher.end()).replaceAll("\\s", "");
            violation(from.get(), pkg, imported).ifPresent(reason -> result.add(relative + ": " + reason));
        }
        return result;
    }

    private static Optional<String> violation(String from, String pkg, String imported) {
        Optional<String> to = moduleOfPackage(pkg);
        if (to.isEmpty() || to.get().equals(from) || to.get().equals(COMMON)) {
            return Optional.empty();
        }
        String target = to.get();
        if (target.equals(CONFIG)) {
            return Optional.of(from + " -> " + imported + " (no module depends on the wiring)");
        }
        if (from.equals(COMMON) || INFRASTRUCTURE.contains(from) && !INFRASTRUCTURE.contains(target)) {
            return Optional.of(from + " -> " + imported + " (infrastructure)");
        }
        String rest = pkg.substring(target.length());
        boolean published = rest.equals(".service")
                || rest.startsWith(".service.")
                || rest.equals(".api")
                || rest.startsWith(".api.");
        return published ? Optional.empty() : Optional.of(from + " -> " + imported);
    }

    /** The module, the wiring ({@code config}) or {@code common} a package belongs to; empty for the root. */
    private static Optional<String> moduleOfPackage(String pkg) {
        Stream<String> owners = Stream.concat(MODULES.stream(), Stream.of(CONFIG, COMMON));
        return owners.filter(owner -> pkg.equals(owner) || pkg.startsWith(owner + "."))
                .findFirst();
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
