package com.smartup24.cms.instance.search;

import com.smartup24.cms.instance.common.entity.EntityDefinition;
import com.smartup24.cms.instance.common.entity.EntityScope;
import com.smartup24.cms.instance.common.security.ScopeFilter;
import com.smartup24.cms.instance.example.service.ExampleOrderEntity;
import com.smartup24.cms.instance.md.service.MdScopeService;
import com.smartup24.cms.instance.md.service.MdUserEntity;
import com.smartup24.cms.instance.ms.note.service.MsNoteEntity;
import com.smartup24.cms.instance.ms.task.service.MsProjectEntity;
import com.smartup24.cms.instance.ms.task.service.MsTaskEntity;
import com.smartup24.cms.instance.search.service.SearchEntities;
import com.smartup24.cms.instance.search.service.SearchEntity;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The entities the search indexes, declared as the application declares them (ADR-0032, 10.3), for tests that build
 * the search module by hand: tasks, projects, users, notes and example orders. A custom scope reads the md module's
 * rule when a scope service is given; without one it restricts nothing, which is enough for a test that only builds
 * documents and never reads a viewer's scope.
 */
final class SearchTestEntities {

    static final String TASKS = MsTaskEntity.CODE;
    static final String PROJECTS = MsProjectEntity.CODE;
    static final String USERS = MdUserEntity.CODE;
    static final String NOTES = MsNoteEntity.CODE;
    static final String ORDERS = ExampleOrderEntity.CODE;

    private SearchTestEntities() {}

    /** The declarations, each custom scope read through {@code scopes} or unrestricted without one. */
    static List<EntityDefinition> declarations(@Nullable MdScopeService scopes) {
        return List.of(
                MsTaskEntity.definition(provider(scopes, Kind.TASKS)),
                MsProjectEntity.definition(provider(scopes, Kind.PROJECTS)),
                MdUserEntity.definition(provider(scopes, Kind.USERS)),
                MsNoteEntity.DEFINITION,
                ExampleOrderEntity.DEFINITION);
    }

    /** The search's entities over the declarations, every module switched on. */
    static SearchEntities of(@Nullable MdScopeService scopes) {
        return new SearchEntities(declarations(scopes), null);
    }

    /** The search's entities without a viewer's scope: for building documents and collections. */
    static SearchEntities unscoped() {
        return of(null);
    }

    /** One of them by code. */
    static SearchEntity entity(String code) {
        return unscoped().find(code).orElseThrow();
    }

    private enum Kind {
        TASKS,
        PROJECTS,
        USERS
    }

    private static EntityScope.ScopeProvider provider(@Nullable MdScopeService scopes, Kind kind) {
        return (userId, alias) -> {
            if (scopes == null) return ScopeFilter.unrestricted();
            return switch (kind) {
                case TASKS -> scopes.filterForTasks(userId);
                case PROJECTS -> scopes.filterForProjects(userId);
                case USERS -> scopes.filterForUsers(userId, alias + ".id");
            };
        };
    }
}
