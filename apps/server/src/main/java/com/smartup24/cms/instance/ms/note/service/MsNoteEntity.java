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
import com.smartup24.cms.instance.common.entity.field.FieldSource.SystemColumn;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The note entity, every field declared once (ADR-0032, 3.2; plan 10/10, item 5.1): {@code form-meta/ms.notes}
 * gives the screen its form, {@link MsNoteService} checks every save by the same fields, and the list
 * {@code ms.notes} ({@code GET /api/v1/notes}, {@code query-meta/ms.notes}) is derived from them. The title fits its
 * column (255); the colours are the ones the screen offers. History, export and bulk delete come from the declaration
 * and {@link MsNoteRecords} (roadmap item 56); the names of its right and its menu item too (roadmap item 57).
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
            .field(select("color", "notes.col.color", COLORS, "notes.color_").column("color"))
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
            .action("pin", "update")
            .archivable()
            .actions("delete")
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
