package com.smartup24.cms.instance.common.entity;

import static com.smartup24.cms.platform.api.entity.field.EntityFields.bool;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.number;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.text;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import com.smartup24.cms.instance.support.TestDatabases;
import com.smartup24.cms.platform.api.entity.Entity;
import com.smartup24.cms.platform.api.entity.EntityCapability;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.EntityScope;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Plan 10/10, item 6.4, acceptance "the schema drift test in CI is green" (ADR-0033, 7; ADR-0032, 11.4): every entity
 * the application declares matches its tables after all migrations, and a table that drifts from its declaration is
 * reported line by line — the same check refuses the start ({@code EntitySchemaGate}).
 */
class EntitySchemaContractTest extends EmbeddedPostgresTest {

    @Autowired
    private EntityRegistry entities;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void everyDeclaredEntityMatchesItsTables() {
        assertThat(entities.all()).extracting(EntityDefinition::code).contains("ms.notes", "md.users", "ms.tasks");
        assertThat(EntitySchemaCheck.of(jdbc).problems(entities.all())).isEmpty();
    }

    private static EntityDefinition books(EntityScope scope) {
        return Entity.define("drift.books", "drift")
                .table("drift_books", "b")
                .scope(scope)
                .field(text("title", "x.title").column("title").required().length(1, 100))
                .field(text("isbn", "x.isbn").column("isbn"))
                .field(number("pages", "x.pages").column("pages"))
                .field(bool("lent", "x.lent").column("lent"))
                .section("main", "entity.section.main", "title", "isbn", "pages", "lent")
                .actions("create", "update", "delete", "archive")
                .defaultSort("title", Entity.Sort.ASC)
                .capabilities(EntityCapability.ARCHIVE)
                .build();
    }

    @Test
    void aTableThatDriftsFromItsDeclarationIsReportedLineByLine() {
        JdbcClient scratch = JdbcClient.create(TestDatabases.migratedCopy("schema_drift"));
        scratch.sql("""
                        create table drift_books (
                            id bigint generated always as identity primary key,
                            title text not null,
                            pages text,
                            lent boolean not null,
                            shelf text not null,
                            org_unit_id bigint,
                            created_by bigint not null, modified_by bigint not null,
                            modified_at timestamptz not null default clock_timestamp(),
                            revision bigint)
                        """).update();

        List<String> problems = EntitySchemaCheck.of(scratch)
                .problems(List.of(books(EntityScope.orgUnit("org_unit_id", "created_by"))));

        assertThat(problems)
                .contains(
                        "drift.books: drift_books.revision may be null",
                        "drift.books: drift_books.created_at is missing (read by the runtime)",
                        "drift.books: drift_books.attributes is missing (read by the runtime)",
                        "drift.books: drift_books.archived_at is missing (the archive (ADR-0032, 5.4))",
                        "drift.books: drift_books.archived_by is missing (the archive (ADR-0032, 5.4))",
                        "drift.books: drift_books.org_unit_id references no md_org_units (the org-unit scope)",
                        "drift.books: drift_books.isbn is missing (field isbn (text))",
                        "drift.books: drift_books.pages is text, field pages (number) needs bigint or double precision"
                                + " or integer or numeric or real or smallint",
                        "drift.books: drift_books.lent is not null without a default, field lent is optional and has"
                                + " no default",
                        "drift.books: drift_books.shelf is not null without a default, and no field of the form or the"
                                + " runtime writes it");
    }

    @Test
    void aMissingTableIsReported() {
        JdbcClient scratch = JdbcClient.create(TestDatabases.migratedCopy("schema_missing"));
        assertThat(EntitySchemaCheck.of(scratch).problems(List.of(books(EntityScope.all()))))
                .containsExactly("drift.books: table drift_books is missing");
    }
}
