package com.smartup24.cms.instance.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.instance.support.TestDatabases;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * ADR-0026: the published read views exist in the migrated schema, publish no secret, refuse writes and are inlined
 * into the reader's query.
 */
class PublishedReadViewsTest {

    private static final Pattern CREATE_VIEW =
            Pattern.compile("(?i)create\\s+(?:or\\s+replace\\s+)?view\\s+([a-z_][a-z0-9_]*)");
    private static final Pattern SECRET = Pattern.compile("password|hash|secret|token");

    private static DataSource database;
    private static JdbcClient jdbc;

    @BeforeAll
    static void migrate() {
        database = TestDatabases.migratedCopy("pub_views");
        jdbc = JdbcClient.create(database);
    }

    @Test
    @DisplayName("ADR-0026: every view the migrations publish exists, and the schema has no other view")
    void everyPublishedViewExists() throws IOException {
        Set<String> declared = declaredViews();
        Set<String> inSchema = new TreeSet<>(jdbc.sql("select viewname from pg_views where schemaname = 'public'")
                .query(String.class)
                .list());
        assertThat(declared).isNotEmpty().allMatch(view -> view.contains("_pub_"));
        assertThat(inSchema).isEqualTo(declared);
    }

    @Test
    @DisplayName("ADR-0026: no published view has a column named like a password, a hash, a secret or a token")
    void noPublishedViewPublishesASecret() {
        List<String> columns = jdbc.sql("""
                        select c.table_name || '.' || c.column_name
                        from information_schema.columns c
                        join pg_views v on v.schemaname = c.table_schema and v.viewname = c.table_name
                        where c.table_schema = 'public' and c.table_name like '%\\_pub\\_%'
                        order by 1
                        """).query(String.class).list();
        assertThat(columns).isNotEmpty();
        assertThat(columns.stream()
                        .filter(column -> SECRET.matcher(column.toLowerCase()).find()))
                .as("secret columns in published views")
                .isEmpty();
    }

    @Test
    @DisplayName("ADR-0026: a write through a published view is refused")
    void writesThroughAPublishedViewAreRefused() throws IOException {
        for (String view : declaredViews()) {
            assertThatThrownBy(() ->
                            jdbc.sql("insert into " + view + " default values").update())
                    .as(view)
                    .rootCause()
                    .hasMessageContaining("published read view " + view + " is read-only");
        }
    }

    @Test
    @DisplayName("ADR-0026: PostgreSQL inlines a published view and uses the base table's index")
    void publishedViewsAreInlined() throws SQLException {
        List<String> plan = new ArrayList<>();
        try (Connection connection = database.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("set local enable_seqscan = off");
                try (ResultSet rows = statement.executeQuery("""
                        explain select t.title, s.name, p.name, u.name
                        from ms_task_pub_tasks t
                        left join ms_task_pub_statuses s on s.id = t.status_id
                        left join ms_task_pub_projects p on p.id = t.project_id
                        left join md_pub_users u on u.id = t.reporter_id
                        where t.id = 1
                        """)) {
                    while (rows.next()) {
                        plan.add(rows.getString(1));
                    }
                }
            } finally {
                connection.rollback();
            }
        }
        String text = String.join("\n", plan);
        assertThat(text).contains("ms_tasks_pkey").doesNotContain("Subquery Scan");
    }

    private static Set<String> declaredViews() throws IOException {
        Set<String> views = new TreeSet<>();
        Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/V*.sql");
        for (Resource file : files) {
            Matcher matcher = CREATE_VIEW.matcher(file.getContentAsString(StandardCharsets.UTF_8));
            while (matcher.find()) {
                views.add(matcher.group(1).toLowerCase());
            }
        }
        return views;
    }
}
