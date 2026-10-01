package com.smartup24.cms.instance.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.instance.search.repository.SearchProjectionReader;
import com.smartup24.cms.instance.support.TestDatabases;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The entity types the code publishes to the search index, read from the source: every call of
 * {@code SearchChangePublisher.changed("TYPE", id)} and every type the publisher's own fan-out statements write.
 * Each of them must pass the {@code search_projection_versions.entity_type} check (V026) and have a projection
 * source: a publish of an unknown type would roll back the business transaction that made it, and a revision
 * with no projection would never be delivered, so a rebuild could not finish.
 *
 * <p>Notes are not published: they have no collection in a search generation, and the global search finds them
 * through PostgreSQL ({@code SearchFallbackRepository}, the owner's notes only).
 */
class SearchProjectionTypesTest {

    private static final Path SOURCES = Path.of("src/main/java/com/smartup24/cms/instance");
    private static final Path PUBLISHER = SOURCES.resolve("search/service/SearchChangePublisher.java");
    private static final Pattern CHANGED_CALL = Pattern.compile("\\.changed\\(\\s*\"([A-Z_]+)\"");
    private static final Pattern FAN_OUT_TYPE = Pattern.compile("'([A-Z_]+)'");

    @Test
    @DisplayName("Every published entity type passes the projection check and has a projection source")
    void everyPublishedTypeIsAcceptedAndProjected() throws IOException {
        Set<String> published = publishedTypes();
        assertThat(published).as("types read from the source").contains("TASK", "PROJECT", "USER");

        JdbcClient jdbc = JdbcClient.create(TestDatabases.migratedCopy("search_projection_types"));
        long id = 1;
        for (String type : published) {
            long entityId = id++;
            assertThatCode(() -> jdbc.sql("""
                                    insert into search_projection_versions (entity_type, entity_id, revision)
                                    values (:type, :id, 1)
                                    """)
                            .param("type", type)
                            .param("id", entityId)
                            .update())
                    .as("search_projection_versions accepts %s", type)
                    .doesNotThrowAnyException();
            assertThatCode(() -> SearchProjectionReader.sourceTable(type))
                    .as("a projection source for %s", type)
                    .doesNotThrowAnyException();
        }
        assertThatThrownBy(() -> jdbc.sql("""
                                insert into search_projection_versions (entity_type, entity_id, revision)
                                values ('NOTE', 1, 1)
                                """).update())
                .as("a type without a projection stays out of the index")
                .hasMessageContaining("search_projection_versions_entity_type_check");
    }

    private static Set<String> publishedTypes() throws IOException {
        Set<String> types = new TreeSet<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                Matcher call = CHANGED_CALL.matcher(Files.readString(file));
                while (call.find()) {
                    types.add(call.group(1));
                }
            }
        }
        Matcher fanOut = FAN_OUT_TYPE.matcher(Files.readString(PUBLISHER));
        while (fanOut.find()) {
            types.add(fanOut.group(1));
        }
        return types;
    }
}
