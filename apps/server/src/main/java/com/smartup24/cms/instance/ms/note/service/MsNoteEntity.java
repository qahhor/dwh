package com.smartup24.cms.instance.ms.note.service;

import com.smartup24.cms.instance.common.entity.EntityCapability;
import com.smartup24.cms.instance.common.entity.EntityDefinition;
import com.smartup24.cms.instance.common.entity.EntityDefinition.EntityAction;
import com.smartup24.cms.instance.common.entity.EntityDefinition.EntityMenu;
import com.smartup24.cms.instance.common.entity.EntityDefinition.EntityRights;
import com.smartup24.cms.instance.common.entity.EntityDefinition.FormSection;
import com.smartup24.cms.instance.common.entity.FormField;
import com.smartup24.cms.instance.common.entity.FormFieldType;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The note entity, declared once (ADR-0019 pilot, roadmap item 54): {@code GET /api/v1/form-meta/ms.notes} gives
 * the screen its form, and {@link MsNoteService} checks every save by the same fields. The title fits its column
 * (255); the colours are the ones the screen offers. History, export and bulk delete come from the declaration
 * and {@link MsNoteRecords} (roadmap item 56); the names of its right and its menu item too (roadmap item 57).
 */
@Configuration
public class MsNoteEntity {

    /** The entity's code and its list's: a constant, so neither declaration waits for the other to load. */
    public static final String CODE = "ms.notes";

    public static final List<String> COLORS = List.of("default", "blue", "green", "yellow", "purple", "red");

    /** Text of a note, generous but bounded, so one request cannot store an unbounded document. */
    public static final int MAX_CONTENT = 100_000;

    public static final EntityDefinition DEFINITION = new EntityDefinition(
            CODE,
            "notes",
            CODE,
            "NOTE",
            "ms_notes",
            new EntityRights(
                    "ms.note",
                    "notes.rights.form",
                    Map.of(
                            "view", "notes.rights.view",
                            "create", "notes.rights.create",
                            "update", "notes.rights.update",
                            "delete", "notes.rights.delete")),
            new EntityMenu("/notes", "nav.notes", "description", "workspace", 30, "notes"),
            List.of(
                    FormField.of("title", "notes.col.title", FormFieldType.TEXT)
                            .asRequired()
                            .length(1, 255),
                    FormField.of("contentMd", "notes.col.content", FormFieldType.MARKDOWN)
                            .length(null, MAX_CONTENT),
                    FormField.select("color", "notes.col.color", COLORS, "notes.color_"),
                    FormField.of("isPinned", "notes.col.pinned", FormFieldType.BOOLEAN)),
            List.of(
                    new FormSection("main", "entity.section.main", List.of("title", "contentMd")),
                    new FormSection("settings", "entity.section.settings", List.of("color", "isPinned"))),
            List.of(
                    new EntityAction("create", "create"),
                    new EntityAction("update", "update"),
                    new EntityAction("pin", "update"),
                    new EntityAction("delete", "delete")),
            Set.of(
                    EntityCapability.CUSTOM_FIELDS,
                    EntityCapability.SAVED_VIEWS,
                    EntityCapability.EXPORT,
                    EntityCapability.HISTORY,
                    EntityCapability.BULK));

    @Bean
    public EntityDefinition msNotesEntity() {
        return DEFINITION;
    }
}
