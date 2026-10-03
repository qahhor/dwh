package com.smartup24.cms.instance.md;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.platform.api.entity.EntityCapability;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.EntityModel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Custom fields live in the record's {@code attributes} column (ADR-0019, 2.3) and are filtered there
 * ({@code attributes @> ...}): an entity with the CUSTOM_FIELDS capability has a GIN index on that column of its table,
 * or a filter on a custom field reads the whole table.
 */
class EntityCustomFieldsIndexTest {

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    @Test
    @DisplayName("ADR-0019: every entity with custom fields has a GIN index on its attributes")
    void everyEntityWithCustomFieldsHasAGinIndex() throws Exception {
        String migrations = migrations();
        List<String> missing = new ArrayList<>();
        int checked = 0;
        for (EntityDefinition entity : EntityActionPermissionContractTest.declaredEntities()) {
            if (!entity.capabilities().contains(EntityCapability.CUSTOM_FIELDS)) {
                continue;
            }
            checked++;
            EntityModel model = entity.model();
            if (model == null) {
                missing.add(entity.code() + " has custom fields but no table");
            } else if (!ginIndex(model.table()).matcher(migrations).find()) {
                missing.add(entity.code() + ": create index ... on " + model.table() + " using gin ("
                        + EntityModel.ATTRIBUTES + " jsonb_path_ops)");
            }
        }
        assertThat(checked).as("entities with custom fields").isPositive();
        assertThat(missing).isEmpty();
    }

    @Test
    @DisplayName("ADR-0019: the index check tells a GIN index on attributes from other indexes")
    void theCheckReadsTheIndexKind() {
        assertThat(ginIndex("ms_notes")
                        .matcher("create index if not exists ms_notes_attributes_gin_idx on ms_notes using gin"
                                + " (attributes jsonb_path_ops);")
                        .find())
                .isTrue();
        assertThat(ginIndex("ms_notes")
                        .matcher("create index ms_notes_title_trgm on ms_notes using gin (title gin_trgm_ops);")
                        .find())
                .isFalse();
        assertThat(ginIndex("ms_notes")
                        .matcher("create index ms_notes_attributes_idx on ms_notes (attributes);")
                        .find())
                .isFalse();
        assertThat(ginIndex("ms_notes")
                        .matcher("create index ms_notes_x on ms_notes_archive using gin (attributes);")
                        .find())
                .isFalse();
    }

    private static Pattern ginIndex(String table) {
        return Pattern.compile("(?is)create\\s+index\\s+(?:concurrently\\s+)?(?:if\\s+not\\s+exists\\s+)?\\w+"
                + "\\s+on\\s+(?:only\\s+)?" + Pattern.quote(table) + "\\s+using\\s+gin\\s*\\(\\s*"
                + EntityModel.ATTRIBUTES
                + "\\b");
    }

    private static String migrations() throws IOException {
        StringBuilder all = new StringBuilder();
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".sql"))
                    .sorted()
                    .toList()) {
                all.append(Files.readString(file, StandardCharsets.UTF_8)).append('\n');
            }
        }
        return all.toString();
    }
}
