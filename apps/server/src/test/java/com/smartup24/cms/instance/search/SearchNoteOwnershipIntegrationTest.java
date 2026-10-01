package com.smartup24.cms.instance.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.search.repository.SearchFallbackRepository;
import com.smartup24.cms.instance.search.repository.SearchIndexStateRepository;
import com.smartup24.cms.instance.search.repository.SearchSettingsRepository;
import com.smartup24.cms.instance.search.service.SearchExecutionSnapshotReader;
import com.smartup24.cms.instance.search.service.SearchPolicyProvider;
import com.smartup24.cms.instance.search.service.SearchResultBudget;
import com.smartup24.cms.instance.search.service.SearchService;
import com.smartup24.cms.instance.search.service.SearchService.SearchHit;
import com.smartup24.cms.instance.search.typesense.TypesenseSearch;
import com.smartup24.cms.instance.support.TestDatabases;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * ADR-0013: a note is its owner's alone. Notes have no Typesense collection; the global search finds them through
 * PostgreSQL, so a note is found by its owner as soon as it is saved, and never by anyone else,
 * an administrator included.
 */
class SearchNoteOwnershipIntegrationTest {

    static JdbcClient jdbc;
    static SearchFallbackRepository fallback;
    static SearchService search;
    static long owner;
    static long administrator;

    @BeforeAll
    static void setUp() {
        jdbc = JdbcClient.create(TestDatabases.migratedCopy("search_note_owner"));
        fallback = new SearchFallbackRepository(jdbc);
        TypesenseSearch typesense = mock(TypesenseSearch.class);
        when(typesense.isEnabled()).thenReturn(false);
        search = new SearchService(
                typesense,
                fallback,
                SearchAccessFixtures.policy(),
                new SearchResultBudget(),
                new SearchPolicyProvider(
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
                        new SearchSettingsRepository(jdbc)),
                new SearchExecutionSnapshotReader(new SearchIndexStateRepository(jdbc)));
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
        assertThat(noteIds(search.search("zephyrine", "NOTE", 10).hits())).containsExactly(Long.toString(mine));
        assertThat(noteIds(search.search("zephyrine", "ALL", 10).hits())).containsExactly(Long.toString(mine));
        assertThat(fallback.searchExact(theirs, "NOTE").groups().getFirst().hits())
                .as("another person's note by its id")
                .isEmpty();

        signIn(administrator);
        assertThat(noteIds(search.search("zephyrine", "NOTE", 10).hits())).containsExactly(Long.toString(theirs));
        assertThat(fallback.searchExact(mine, "NOTE").groups().getFirst().hits())
                .isEmpty();
    }

    @Test
    @DisplayName("ADR-0013: without a signed-in person the note search finds nothing")
    void withoutAPersonNoNoteIsFound() {
        note("Orphaned quokka", owner);
        assertThat(fallback.search("quokka", "NOTE", 10).groups().getFirst().hits())
                .isEmpty();
    }

    private static java.util.List<String> noteIds(java.util.List<SearchHit> hits) {
        return hits.stream()
                .filter(hit -> hit.entityType().equals("NOTE"))
                .map(SearchHit::id)
                .toList();
    }

    /** The legacy wildcard opens the global search without a role lookup (ADR-0013 §2.5). */
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
