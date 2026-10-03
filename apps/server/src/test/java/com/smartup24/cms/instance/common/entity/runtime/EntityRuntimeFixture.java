package com.smartup24.cms.instance.common.entity.runtime;

import static com.smartup24.cms.platform.api.entity.field.EntityFields.bool;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.date;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.instant;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.ref;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.sortable;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.text;

import com.smartup24.cms.instance.ms.note.service.MsNoteEntity;
import com.smartup24.cms.platform.api.entity.Entity;
import com.smartup24.cms.platform.api.entity.EntityCapability;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.EntityScope;
import com.smartup24.cms.platform.api.entity.event.EntityChanged;
import com.smartup24.cms.platform.api.entity.field.FieldSource.SystemColumn;
import com.smartup24.cms.platform.api.entity.hook.EntityActionCall;
import com.smartup24.cms.platform.api.entity.hook.EntityActionHandler;
import com.smartup24.cms.platform.api.entity.hook.EntityCommitted;
import com.smartup24.cms.platform.api.entity.hook.EntityHooks;
import com.smartup24.cms.platform.api.entity.hook.EntitySave;
import com.smartup24.cms.platform.api.entity.hook.Rules;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * A test entity on the runtime with what an author writes besides the declaration (ADR-0032, 6.5–6.7): a rule, hooks
 * that set a value, refuse a save and fail after the commit, an action handler, and a reference to notes by the notes'
 * entity code. Its table {@value #TABLE} is created by {@link EntityRuntimeIntegrationTest} and dropped after it.
 */
@TestConfiguration
class EntityRuntimeFixture {

    static final String CODE = "test.rtitems";
    static final String TABLE = "test_rt_items";

    /** A title the hook refuses, one whose afterCommit hook fails. */
    static final String REFUSED = "refused by the hook";

    static final String FAILS_AFTER_COMMIT = "fails after commit";

    static final List<String> SAVES = new CopyOnWriteArrayList<>();
    static final List<Long> COMMITTED = new CopyOnWriteArrayList<>();
    static final List<Long> FAILING = new CopyOnWriteArrayList<>();
    static final List<EntityChanged> EVENTS = new CopyOnWriteArrayList<>();

    static final String DDL = """
            create table if not exists test_rt_items (
                id bigint generated always as identity primary key,
                title text not null,
                code text,
                note_id bigint references ms_notes (id),
                starts_on date,
                ends_on date,
                starred boolean not null default false,
                attributes jsonb not null default '{}',
                created_by bigint not null references md_users (id),
                modified_by bigint not null references md_users (id),
                created_at timestamptz not null default clock_timestamp(),
                modified_at timestamptz not null default clock_timestamp(),
                revision bigint not null default 1);
            create index if not exists test_rt_items_note_id_idx on test_rt_items (note_id);
            create index if not exists test_rt_items_created_by_idx on test_rt_items (created_by);
            create index if not exists test_rt_items_modified_by_idx on test_rt_items (modified_by)
            """;

    static final EntityDefinition DEFINITION = Entity.define(CODE, "notes")
            .table(TABLE, "r")
            .scope(EntityScope.owner("created_by"))
            .field(text("title", "notes.col.title")
                    .column("title")
                    .required()
                    .length(1, 50)
                    .list(sortable().searchable()))
            .field(text("code", "notes.col.title").column("code").readonly())
            .field(ref("noteId", "notes.col.title").column("note_id").target(MsNoteEntity.CODE, "title"))
            .field(date("startsOn", "notes.col.created_at").column("starts_on"))
            .field(date("endsOn", "notes.col.modified_at").column("ends_on"))
            .field(bool("starred", "notes.col.pinned").column("starred").readonly())
            .field(instant("modifiedAt", "notes.col.modified_at")
                    .system(SystemColumn.MODIFIED_AT)
                    .list(sortable()))
            .section("main", "entity.section.main", "title", "code", "noteId", "startsOn", "endsOn", "starred")
            .rule("period", Rules.notBefore("endsOn", "startsOn"))
            .actions("create", "update", "delete")
            .action("star", "update")
            .defaultSort("modifiedAt", Entity.Sort.DESC)
            .auditTable(TABLE)
            .capabilities(EntityCapability.HISTORY)
            .build();

    @Bean
    EntityDefinition testRuntimeItems() {
        return DEFINITION;
    }

    /** The hooks: a code from the title before a save, a refusal, a record of each save and commit. */
    @Bean
    EntityHooks testRuntimeItemHooks() {
        return new EntityHooks() {
            @Override
            public String entity() {
                return CODE;
            }

            @Override
            public void beforeSave(EntitySave save) {
                String title = save.values().text("title");
                if (REFUSED.equals(title)) {
                    save.reject("title", "refused", "error.field.unknown", Map.of());
                }
                if (title != null && save.changed("title")) {
                    save.values()
                            .set("code", "RT-" + title.toUpperCase(Locale.ROOT).replace(' ', '-'));
                }
            }

            @Override
            public void afterSave(EntitySave save) {
                SAVES.add(save.operation() + " " + save.id());
                if (FAILS_AFTER_COMMIT.equals(save.values().text("title"))) {
                    FAILING.add(save.id());
                }
            }

            @Override
            public void afterCommit(EntityCommitted committed) {
                COMMITTED.add(committed.change().id());
                if (FAILING.contains(committed.change().id())) {
                    throw new IllegalStateException("The afterCommit hook of a test record fails on purpose");
                }
            }
        };
    }

    /** The action {@code star}: the record is starred; its value is the server's to write. */
    @Bean
    EntityActionHandler testRuntimeItemStar() {
        return new EntityActionHandler() {
            @Override
            public String entity() {
                return CODE;
            }

            @Override
            public String action() {
                return "star";
            }

            @Override
            public void run(EntityActionCall call) {
                if (Boolean.TRUE.equals(call.values().bool("starred"))) {
                    call.reject("", "already_starred", "error.field.unknown", Map.of());
                }
                call.values().set("starred", true);
            }
        };
    }

    /** Every change the runtime commits, as a module listening after the commit sees it. */
    @Bean
    Object testRuntimeItemEvents() {
        return new Object() {
            @TransactionalEventListener
            public void committed(EntityChanged change) {
                if (CODE.equals(change.entity())) EVENTS.add(change);
            }
        };
    }
}
