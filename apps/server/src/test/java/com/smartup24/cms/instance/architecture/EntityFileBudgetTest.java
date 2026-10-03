package com.smartup24.cms.instance.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.md.EntityActionPermissionContractTest;
import com.smartup24.cms.instance.md.EntityActionPermissionContractTest.Declaration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plan 10/10, item 5.4, acceptance "notes are at most two server files" (ADR-0032, 15, step 5), for every entity: on
 * the general runtime an entity is its declaration and, when it needs them, its hooks — no controller, service or
 * repository of its own on the server, no file of its own under {@code apps/web/src/app/features}. An entity with a
 * screen or an API of its own is listed below with the files it keeps and the reason; the lists are exact, so a new
 * file fails and so does a listed one that is gone.
 */
class EntityFileBudgetTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final Path NOTES = MAIN.resolve("com/smartup24/cms/instance/ms/note");
    private static final Path FEATURES = Path.of("../web/src/app/features");

    /** Per-entity server classes beyond the declaration and its hooks, named after the declaration. */
    private static final Pattern OWN_CLASS =
            Pattern.compile("(Controller|Service|Repository|Records|Query|Actions|Views?)\\.java");

    /** Server classes an entity keeps beside its declaration and hooks, by entity code, with the reason. */
    private static final Map<String, Set<String>> SERVER_EXCEPTIONS = Map.of(
            // The task card: comments, files, members, the status action and the deadline reminder (FR-WORK-01).
            "ms.tasks",
            Set.of("MsTaskController.java", "MsTaskRepository.java", "MsTaskViews.java"),
            // Project members and progress (FR-WORK-02): the member list and the progress endpoint.
            "ms.projects",
            Set.of(
                    "MsProjectController.java",
                    "MsProjectQuery.java",
                    "MsProjectRepository.java",
                    "MsProjectService.java"),
            // The task's status action and notifications read a status by code; the statuses are edited generically.
            "ms.task_statuses",
            Set.of("MsTaskStatusRepository.java"),
            // Invitations, passwords, sessions and the role assignment of a user (FR-IAM-01, FR-AUTH-04).
            "md.users",
            Set.of("MdUserActions.java", "MdUserRepository.java", "MdUserService.java", "MdUserView.java"));

    /** Web feature files that name an entity, by entity code, with the reason. */
    private static final Map<String, Set<String>> WEB_EXCEPTIONS = Map.of(
            // The notes board: cards, colours and pinning instead of a list (FR-NOTE-01).
            "ms.notes",
            Set.of("notes/notes.api.ts"),
            // The tasks board, table and card of the tasks screen (FR-WORK-01).
            "ms.tasks",
            Set.of(
                    "tasks/services/task-filter.service.ts",
                    "tasks/services/task-forms.service.ts",
                    "tasks/services/task-list.store.ts",
                    "tasks/tasks.api.ts"),
            // The project cards and the members dialog (FR-WORK-02).
            "ms.projects",
            Set.of("tasks/projects/projects.api.ts", "tasks/projects/projects.component.ts"),
            // Read by the tasks screen as its dictionaries; edited on the generic screen.
            "ms.task_types",
            Set.of("tasks/services/task-dictionaries.service.ts"),
            "ms.task_statuses",
            Set.of("tasks/services/task-dictionaries.service.ts"),
            // The security tab of the generic user screen (ADR-0032 7.2 overrides).
            "md.users",
            Set.of("iam/users/components/user-security-tab.component.ts", "iam/users/users.overrides.ts"));

    @Test
    @DisplayName("5.4: the notes module is at most two server files besides package-info")
    void notesAreAtMostTwoServerFiles() throws IOException {
        List<String> files;
        try (Stream<Path> tree = Files.walk(NOTES)) {
            files = tree.filter(path -> path.toString().endsWith(".java"))
                    .map(path -> path.getFileName().toString())
                    .filter(name -> !name.equals("package-info.java"))
                    .sorted()
                    .toList();
        }
        assertThat(files).as("the server files of ms.note").isNotEmpty().hasSizeLessThanOrEqualTo(2);
        assertThat(files)
                .as("no controller, service or repository")
                .noneMatch(name -> name.matches(".*(Controller|Service|Repository|Records)\\.java"));
    }

    @Test
    @DisplayName("5.4: every entity is its declaration and hooks on the server, apart from the listed exceptions")
    void everyEntityIsItsDeclarationAndHooks() throws Exception {
        List<String> serverFiles = fileNames(MAIN, ".java");
        Map<String, Set<String>> found = new TreeMap<>();
        Map<String, Set<String>> expected = new TreeMap<>();
        for (Declaration declaration : EntityActionPermissionContractTest.declarations()) {
            String code = declaration.entity().code();
            String prefix = declaration.type().getSimpleName().replaceFirst("Entity$", "");
            Set<String> own = new TreeSet<>();
            for (String name : serverFiles) {
                if (name.startsWith(prefix)
                        && OWN_CLASS.matcher(name.substring(prefix.length())).matches()) {
                    own.add(name);
                }
            }
            assertThat(serverFiles.stream()
                            .filter(name -> name.equals(prefix + "Entity.java") || name.equals(prefix + "Hooks.java")))
                    .as("the declaration and hooks of %s", code)
                    .hasSizeBetween(1, 2);
            found.put(code, own);
            expected.put(code, new TreeSet<>(SERVER_EXCEPTIONS.getOrDefault(code, Set.of())));
        }
        assertThat(found).isEqualTo(expected);
        assertThat(expected.keySet()).containsAll(SERVER_EXCEPTIONS.keySet());
    }

    @Test
    @DisplayName("5.4: no generic entity has a file of its own under apps/web/src/app/features")
    void noGenericEntityHasAWebFeature() throws Exception {
        List<Path> webFiles;
        try (Stream<Path> tree = Files.walk(FEATURES)) {
            webFiles = tree.filter(path -> path.toString().endsWith(".ts"))
                    .filter(path -> !path.toString().endsWith(".spec.ts"))
                    .sorted()
                    .toList();
        }
        Map<Path, String> sources = new TreeMap<>();
        for (Path file : webFiles) {
            sources.put(file, Files.readString(file));
        }
        Map<String, Set<String>> found = new TreeMap<>();
        Map<String, Set<String>> expected = new TreeMap<>();
        for (Declaration declaration : EntityActionPermissionContractTest.declarations()) {
            String code = declaration.entity().code();
            Pattern named = Pattern.compile("['\"`]" + Pattern.quote(code) + "['\"`/]");
            Set<String> files = new TreeSet<>();
            sources.forEach((file, text) -> {
                if (named.matcher(text).find()) {
                    files.add(FEATURES.relativize(file).toString().replace('\\', '/'));
                }
            });
            found.put(code, files);
            expected.put(code, new TreeSet<>(WEB_EXCEPTIONS.getOrDefault(code, Set.of())));
        }
        assertThat(found).isEqualTo(expected);
        assertThat(expected.keySet()).containsAll(WEB_EXCEPTIONS.keySet());
    }

    private static List<String> fileNames(Path root, String suffix) throws IOException {
        try (Stream<Path> tree = Files.walk(root)) {
            return tree.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(suffix))
                    .toList();
        }
    }
}
