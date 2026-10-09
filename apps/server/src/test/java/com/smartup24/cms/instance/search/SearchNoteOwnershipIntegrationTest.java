package com.smartup24.cms.instance.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.search.api.SearchManagementDtos.SearchExecutionSnapshot;
import com.smartup24.cms.instance.search.api.SearchManagementDtos.SettingsSnapshot;
import com.smartup24.cms.instance.search.api.SearchOwnerRateLimits;
import com.smartup24.cms.instance.search.repository.SearchIndexStateRepository;
import com.smartup24.cms.instance.search.repository.SearchSettingsRepository;
import com.smartup24.cms.instance.search.service.SearchEntities;
import com.smartup24.cms.instance.search.service.SearchExecutionSnapshotReader;
import com.smartup24.cms.instance.search.service.SearchPolicyProvider;
import com.smartup24.cms.instance.search.service.SearchQueryPolicy;
import com.smartup24.cms.instance.search.service.SearchService;
import com.smartup24.cms.instance.search.service.SearchService.SearchHit;
import com.smartup24.cms.instance.search.typesense.TypesenseSearch;
import com.smartup24.cms.instance.search.typesense.TypesenseSearch.CollectionQuery;
import com.smartup24.cms.instance.search.typesense.TypesenseSearch.CollectionSearch;
import com.smartup24.cms.instance.support.TestDatabases;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * ADR-0013, 2.5: a note is its owner's alone, in the global search too (ADR-0032, 10.3). Its documents carry its owner,
 * the index query asks only for the caller's own notes, and every hit is checked again in the database with the
 * owner's predicate — so even an index that answered another person's note (stale, or wrong) shows nothing of it; an
 * administrator is no exception. Without Typesense the database finds them with the same predicate.
 */
class SearchNoteOwnershipIntegrationTest {

    static JdbcClient jdbc;
    static SearchEntities entities;
    static SearchService search;
    static long owner;
    static long administrator;

    @BeforeAll
    static void setUp() {
        jdbc = JdbcClient.create(TestDatabases.migratedCopy("search_note_owner"));
        entities = SearchTestEntities.unscoped();
        TypesenseSearch typesense = mock(TypesenseSearch.class);
        when(typesense.isEnabled()).thenReturn(false);
        search = SearchAccessFixtures.service(
                typesense,
                jdbc,
                policies(),
                new SearchExecutionSnapshotReader(new SearchIndexStateRepository(jdbc, entities)),
                entities);
        owner = user("note-owner");
        administrator = user("note-admin");
    }

    @AfterEach
    void signOut() {
        SecurityContext.clear();
    }

    @Test
    @DisplayName("ADR-0013: a created note is found by its owner and by nobody else")
    void aCreatedNoteIsFoundByItsOwnerOnly() {
        long mine = note("Quarterly plan zephyrine", owner);
        long theirs = note("Zephyrine of another person", administrator);

        signIn(owner);
        assertThat(noteIds(search.search("zephyrine", "ms.notes", 10).hits())).containsExactly(Long.toString(mine));
        assertThat(noteIds(search.search("zephyrine", "ALL", 10).hits())).containsExactly(Long.toString(mine));
        assertThat(search.search("#" + theirs, "ms.notes", 10).hits())
                .as("another person's note by its id")
                .isEmpty();

        signIn(administrator);
        assertThat(noteIds(search.search("zephyrine", "ms.notes", 10).hits())).containsExactly(Long.toString(theirs));
        assertThat(search.search("#" + mine, "ms.notes", 10).hits()).isEmpty();
    }

    @Test
    @DisplayName("ADR-0032, 10.3: the index asks for the caller's notes, and a hit of another's is dropped")
    void anIndexHitOfAnotherPersonsNoteIsNeverAnswered() {
        long mine = note("Stale index quokka", owner);
        long theirs = note("Stale index quokka too", administrator);
        TypesenseSearch typesense = mock(TypesenseSearch.class);
        when(typesense.isEnabled()).thenReturn(true);
        // An index that answers both notes, as a stale or a wrong one would.
        when(typesense.multiSearch(anyString(), anyList()))
                .thenReturn(
                        List.of(new CollectionSearch(SearchTestEntities.NOTES, List.of(hit(theirs), hit(mine)), 2, 1)));
        SearchExecutionSnapshotReader snapshots = mock(SearchExecutionSnapshotReader.class);
        when(snapshots.read())
                .thenReturn(new SearchExecutionSnapshot(
                        new SearchIndexStateRepository.IndexSnapshot(
                                UUID.randomUUID(),
                                1,
                                Map.of(SearchTestEntities.NOTES, "fixture_notes"),
                                "MIXED",
                                true,
                                false),
                        new SettingsSnapshot(1, SearchQueryPolicy.defaults())));
        SearchService indexed = SearchAccessFixtures.service(typesense, jdbc, policies(), snapshots, entities);

        signIn(owner);
        var result = indexed.search("quokka", SearchTestEntities.NOTES, 10);

        assertThat(result.source()).isEqualTo("TYPESENSE");
        assertThat(noteIds(result.hits())).containsExactly(Long.toString(mine));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CollectionQuery>> queries = ArgumentCaptor.forClass(List.class);
        verify(typesense).multiSearch(org.mockito.ArgumentMatchers.eq("quokka"), queries.capture());
        assertThat(queries.getValue())
                .singleElement()
                .satisfies(query -> assertThat(query.filterBy()).isEqualTo("scope_users:=" + owner));
    }

    @Test
    @DisplayName("ADR-0013: without a signed-in person the search refuses")
    void withoutAPersonTheSearchRefuses() {
        note("Orphaned quokka", owner);
        assertThatThrownBy(() -> search.search("quokka", SearchTestEntities.NOTES, 10))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
    }

    private static SearchHit hit(long id) {
        return new SearchHit(SearchTestEntities.NOTES, Long.toString(id), "Note", "", "/e/ms.notes/" + id);
    }

    private static SearchPolicyProvider policies() {
        return new SearchPolicyProvider(
                new SearchOwnerRateLimits() {
                    @Override
                    public int userPerMinute() {
                        return 600;
                    }

                    @Override
                    public int tokenPerMinute() {
                        return 300;
                    }
                },
                new SearchSettingsRepository(jdbc));
    }

    private static List<String> noteIds(List<SearchHit> hits) {
        return hits.stream()
                .filter(hit -> hit.entityType().equals(SearchTestEntities.NOTES))
                .map(SearchHit::id)
                .toList();
    }

    /** A person with every right: the note is still the owner's alone (ADR-0013, 2.5). */
    private static void signIn(long userId) {
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                userId, "admin", "admin@example.invalid", 1L, false, Set.of("*.*"), 1, false, 0, null));
    }

    /** A note of {@code owner}, as the entity runtime stores it (ADR-0032, 6). */
    private static long note(String title, long owner) {
        return jdbc.sql("""
                        insert into ms_notes (title, content_md, created_by, modified_by)
                        values (:title, 'body', :owner, :owner)
                        returning id
                        """)
                .param("title", title)
                .param("owner", owner)
                .query(Long.class)
                .single();
    }

    private static long user(String login) {
        return jdbc.sql("""
                        insert into md_users (name, login, email, password_hash, state, language, timezone)
                        values (:login, :login, :login || '@test.local', 'x', 'A', 'ru', 'UTC')
                        returning id
                        """).param("login", login).query(Long.class).single();
    }
}
