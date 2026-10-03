package com.smartup24.cms.instance.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.instance.common.query.QueryPlan;
import com.smartup24.cms.instance.search.repository.SearchDocumentSql;
import com.smartup24.cms.instance.search.repository.SearchFallbackRepository;
import com.smartup24.cms.instance.search.repository.SearchProjectionReader;
import com.smartup24.cms.instance.search.service.SearchEntities;
import com.smartup24.cms.instance.search.service.SearchEntity;
import com.smartup24.cms.instance.support.TestDatabases;
import com.smartup24.cms.platform.api.entity.EntityCapability;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/**
 * The indexed types are the codes of the entities with the SEARCH capability (ADR-0032, 10.3; plan 10/10, item 5.8):
 * each passes the {@code search_projection_versions.entity_type} check, and the SQL the search builds from its
 * declaration — its document, the discovery of its records, their count, the database check of hits, the PostgreSQL
 * search and the exact lookup — runs on the migrated schema. A fixed name of before ({@code TASK}) is refused.
 */
class SearchProjectionTypesTest {

    private static final QueryPlan.SqlFragment NO_SCOPE = new QueryPlan.SqlFragment("", Map.of());

    static JdbcClient jdbc;
    static SearchEntities entities;

    @BeforeAll
    static void setUp() {
        jdbc = JdbcClient.create(TestDatabases.migratedCopy("search_projection_types"));
        entities = SearchTestEntities.unscoped();
    }

    @Test
    @DisplayName("ADR-0032, 10.3: the entities with the SEARCH capability are the indexed types")
    void theIndexedTypesAreTheEntitiesThatDeclareTheSearch() {
        assertThat(entities.codes())
                .containsExactly("example.orders", "md.users", "ms.notes", "ms.projects", "ms.tasks");
        assertThat(entities.all())
                .allSatisfy(
                        entity -> assertThat(entity.definition().capabilities()).contains(EntityCapability.SEARCH));
    }

    @Test
    @DisplayName("Every indexed type passes the projection check and every query of it runs")
    void everyIndexedTypeIsAcceptedAndItsQueriesRun() {
        var reader = new SearchProjectionReader(jdbc, new ObjectMapper(), entities);
        var repository = new SearchFallbackRepository(jdbc);
        long id = 1;
        for (SearchEntity entity : entities.all()) {
            long entityId = id++;
            assertThatCode(() -> jdbc.sql("""
                                    insert into search_projection_versions (entity_type, entity_id, revision)
                                    values (:type, :id, 1)
                                    """)
                            .param("type", entity.code())
                            .param("id", entityId)
                            .update())
                    .as("search_projection_versions accepts %s", entity.code())
                    .doesNotThrowAnyException();
            assertThat(reader.read(entity.code(), entityId))
                    .as("the document of a missing %s is a tombstone", entity.code())
                    .hasValueSatisfying(
                            projection -> assertThat(projection.document()).isNull());
            assertThat(reader.reconciliationIds(entity.code(), 0, 10)).contains(entityId);
            assertThat(jdbc.sql(SearchDocumentSql.count(entity))
                            .query(Long.class)
                            .single())
                    .isNotNegative();
            assertThat(repository.search(entity, "probe", List.of("probe"), 5, NO_SCOPE))
                    .isEmpty();
            assertThat(repository.exact(entity, entityId, NO_SCOPE)).isEmpty();
            assertThat(repository.visible(entity, List.of(entityId), NO_SCOPE)).isEmpty();
        }
        assertThatCode(reader::estimateSerializedBytes).doesNotThrowAnyException();
        assertThatThrownBy(() -> jdbc.sql("""
                                insert into search_projection_versions (entity_type, entity_id, revision)
                                values ('TASK', 1, 1)
                                """).update())
                .as("a fixed type name of before is no entity code")
                .hasMessageContaining("search_projection_versions_ck_entity_type");
    }
}
