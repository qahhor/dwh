package com.smartup24.cms.instance.ms.note.service;

import static com.smartup24.cms.instance.common.entity.field.EntityFields.bool;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.instant;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.markdown;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.searchable;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.select;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.sortable;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.text;

import com.smartup24.cms.instance.common.entity.Entity;
import com.smartup24.cms.instance.common.entity.EntityCapability;
import com.smartup24.cms.instance.common.entity.EntityDefinition;
import com.smartup24.cms.instance.common.entity.EntityDefinition.EntityMenu;
import com.smartup24.cms.instance.common.entity.EntityScope;
import com.smartup24.cms.instance.common.entity.field.FieldDefault;
import com.smartup24.cms.instance.common.entity.field.FieldSource.SystemColumn;
import com.smartup24.cms.instance.common.entity.search.EntitySearchSpec;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The note entity — the whole server side of notes (ADR-0032, 3.2 and 6; plan 10/10, items 5.1 and 5.4): the general
 * runtime serves its records at {@code /api/v1/entities/ms.notes} — list, read, create, change, archive, delete — with
 * its scope, field rules, audit and events; {@code form-meta/ms.notes} gives the screen its form and
 * {@code query-meta/ms.notes} its list, history, export and bulk actions come from the capabilities. Notes need no
 * hooks: pinning is a change of {@code isPinned} with the right {@code update}. The title fits its column (255); the
 * colours are the ones the screen offers.
 *
 * <p>Pinned notes come first, then the most recently changed: the hidden sort key {@code rank} joins the pin flag and
 * the change time into one text value, so the registry's keyset on one field and the row id keeps that order.
 */
@Configuration
public class MsNoteEntity {

    /** The entity's code and its list's. */
    public static final String CODE = "ms.notes";

    public static final List<String> COLORS = List.of("default", "blue", "green", "yellow", "purple", "red");

    /** Text of a note, generous but bounded, so one request cannot store an unbounded document. */
    public static final int MAX_CONTENT = 100_000;

    private static final String RANK = "(case when n.is_pinned then '1' else '0' end"
            + " || to_char(n.modified_at at time zone 'UTC', 'YYYYMMDDHH24MISSUS'))";

    public static final EntityDefinition DEFINITION = Entity.define(CODE, "notes")
            .table("ms_notes", "n")
            // A note is its owner's alone, whatever the role's rule (ADR-0013, 2.5).
            .scope(EntityScope.owner("created_by"))
            .rights(
                    "ms.note",
                    "notes.rights.form",
                    Map.of(
                            "view", "notes.rights.view",
                            "create", "notes.rights.create",
                            "update", "notes.rights.update",
                            "delete", "notes.rights.delete"))
            .menu(new EntityMenu("/notes", "nav.notes", "description", "workspace", 30, "notes"))
            .field(text("title", "notes.col.title")
                    .column("title")
                    .required()
                    .length(1, 255)
                    .list(sortable().searchable()))
            .field(markdown("contentMd", "notes.col.content")
                    .column("content_md")
                    .length(null, MAX_CONTENT)
                    .list(searchable().hidden()))
            .field(select("color", "notes.col.color", COLORS, "notes.color_")
                    .column("color")
                    .required()
                    .defaultValue(FieldDefault.fixed("default")))
            .field(bool("isPinned", "notes.col.pinned").column("is_pinned"))
            .field(instant("modifiedAt", "notes.col.modified_at")
                    .system(SystemColumn.MODIFIED_AT)
                    .list(sortable()))
            .field(instant("createdAt", "notes.col.created_at")
                    .system(SystemColumn.CREATED_AT)
                    .list(sortable().hidden()))
            .field(text("rank", "notes.col.rank")
                    .expression(RANK)
                    .listOnly(sortable().notFilterable().hidden()))
            .section("main", "entity.section.main", "title", "contentMd")
            .section("settings", "entity.section.settings", "color", "isPinned")
            .actions("create", "update")
            .archivable()
            .actions("delete")
            // Found by the global search, its owner's alone like every read of a note (ADR-0013, 2.5; ADR-0032, 10.3).
            .search(EntitySearchSpec.title("title").body("contentMd"))
            .defaultSort("rank", Entity.Sort.DESC)
            .customFields("NOTE")
            .capabilities(
                    EntityCapability.SAVED_VIEWS,
                    EntityCapability.EXPORT,
                    EntityCapability.HISTORY,
                    EntityCapability.BULK)
            .build();

    @Bean
    public EntityDefinition msNotesEntity() {
        return DEFINITION;
    }
}
