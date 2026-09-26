package com.greenwhite.dwh.instance.ms.note.service;

import com.greenwhite.dwh.instance.common.entity.EntityCapability;
import com.greenwhite.dwh.instance.common.entity.EntityDefinition;
import com.greenwhite.dwh.instance.common.entity.EntityDefinition.EntityAction;
import com.greenwhite.dwh.instance.common.entity.EntityDefinition.EntityMenu;
import com.greenwhite.dwh.instance.common.entity.EntityDefinition.EntityRights;
import com.greenwhite.dwh.instance.common.entity.EntityDefinition.FormSection;
import com.greenwhite.dwh.instance.common.entity.FormField;
import com.greenwhite.dwh.instance.common.entity.FormFieldType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The note entity, declared once (ADR-0019 pilot, roadmap item 54): {@code GET /api/v1/form-meta/ms.notes} gives
 * the screen its form, and {@link MsNoteService} checks every save by the same fields. The title fits its column
 * (255); the colours are the ones the screen offers. History, export and bulk delete come from the declaration
 * and {@link MsNoteRecords} (roadmap item 56); the names of its right and its menu item too (roadmap item 57).
 */
@Configuration
public class MsNoteEntity {

    public static final List<String> COLORS = List.of("default", "blue", "green", "yellow", "purple", "red");

    /** Text of a note, generous but bounded, so one request cannot store an unbounded document. */
    public static final int MAX_CONTENT = 100_000;

    public static final EntityDefinition DEFINITION = new EntityDefinition(
            MsNoteQuery.LIST.code(),
            "notes",
            MsNoteQuery.LIST.code(),
            "NOTE",
            "ms_notes",
            new EntityRights("ms.note", "Заметки", Map.of(
                    "view", "Просмотр заметок",
                    "create", "Создание заметки",
                    "update", "Редактирование и закрепление заметки",
                    "delete", "Удаление заметки")),
            new EntityMenu("/notes", "nav.notes", "description", "workspace", 30, "notes"),
            List.of(
                    FormField.of("title", "notes.col.title", FormFieldType.TEXT).asRequired().length(1, 255),
                    FormField.of("contentMd", "notes.col.content", FormFieldType.MARKDOWN).length(null, MAX_CONTENT),
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
            Set.of(EntityCapability.CUSTOM_FIELDS, EntityCapability.SAVED_VIEWS, EntityCapability.EXPORT,
                    EntityCapability.HISTORY, EntityCapability.BULK));

    @Bean
    public EntityDefinition msNotesEntity() {
        return DEFINITION;
    }
}
