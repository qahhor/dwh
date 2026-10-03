package com.acme.library;

import static com.smartup24.cms.platform.api.entity.field.EntityFields.bool;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.instant;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.number;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.sortable;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.text;

import com.smartup24.cms.platform.api.entity.Entity;
import com.smartup24.cms.platform.api.entity.EntityCapability;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.EntityDefinition.EntityMenu;
import com.smartup24.cms.platform.api.entity.EntityScope;
import com.smartup24.cms.platform.api.entity.field.FieldSource.SystemColumn;
import com.smartup24.cms.platform.api.entity.hook.EntityHooks;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The library module (ADR-0033, 8): a module built outside the monorepo against the platform API alone. Its manifest
 * ({@code META-INF/smartupcms/modules/library.json}) names this configuration; the platform imports it after the
 * manifest check, applies the module's migrations and takes its messages, and the general runtime serves its books at
 * {@code /api/v1/entities/library.books}.
 */
@Configuration(proxyBeanMethods = false)
public class LibraryModule {

    /** The code of the books entity. */
    public static final String BOOKS = "library.books";

    /** The module's code in the registry, the switch of its menu item. */
    public static final String MODULE = "library";

    /** The books of a reader: each reader sees the books they added. */
    public static final EntityDefinition DEFINITION = Entity.define(BOOKS, BOOKS)
            .table("lib_books", "b")
            .scope(EntityScope.owner("created_by"))
            .rights(
                    MODULE,
                    "library.books.rights.form",
                    Map.of(
                            "view", "library.books.rights.view",
                            "create", "library.books.rights.create",
                            "update", "library.books.rights.update",
                            "archive", "library.books.rights.archive",
                            "delete", "library.books.rights.delete"))
            .menu(new EntityMenu("nav.library_books", "menu_book", "workspace", 120, MODULE))
            .field(text("title", "library.books.col.title")
                    .column("title")
                    .required()
                    .length(1, 200)
                    .list(sortable().searchable()))
            .field(text("isbn", "library.books.col.isbn")
                    .column("isbn")
                    .length(null, 17)
                    .matching("[0-9-]+")
                    .list(sortable()))
            .field(number("pages", "library.books.col.pages")
                    .column("pages")
                    .range(BigDecimal.ONE, new BigDecimal("100000"))
                    .scale(0)
                    .list(sortable()))
            .field(bool("lent", "library.books.col.lent").column("lent"))
            .field(instant("modifiedAt", "library.books.col.modified_at")
                    .system(SystemColumn.MODIFIED_AT)
                    .list(sortable()))
            .section("main", "entity.section.main", "title", "isbn", "pages", "lent")
            .actions("create", "update", "archive", "delete")
            .defaultSort("modifiedAt", Entity.Sort.DESC)
            .auditTable("lib_books")
            .capabilities(
                    EntityCapability.ARCHIVE,
                    EntityCapability.HISTORY,
                    EntityCapability.EXPORT,
                    EntityCapability.SAVED_VIEWS,
                    EntityCapability.BULK)
            .build();

    @Bean
    public EntityDefinition libraryBooks() {
        return DEFINITION;
    }

    @Bean
    public EntityHooks libraryBookHooks() {
        return new LibraryBookHooks();
    }
}
