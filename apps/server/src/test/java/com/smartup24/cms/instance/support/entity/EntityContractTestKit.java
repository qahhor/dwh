package com.smartup24.cms.instance.support.entity;

import com.smartup24.cms.instance.common.entity.EntityDefinition;
import com.smartup24.cms.instance.common.entity.EntityRegistry;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.context.WebApplicationContext;

/**
 * The contract every entity with a table passes (ADR-0032, 11; plan 10/10, item 6.2): one subclass per entity, and the
 * kit derives its cases from the declaration — the fields and their types, the scope, the field rights, the archive,
 * the actions and the rights of each (ADR-0028) — and runs them against the whole application on the embedded
 * PostgreSQL of the build:
 *
 * <ul>
 *   <li>metadata: {@code form-meta} and {@code query-meta} give every declared field with its type;
 *   <li>CRUD: 201 with {@code Location} and {@code ETag}, the record reads back as written, is listed, is changed and
 *       deleted, a create repeated with its {@code Idempotency-Key} answers the same record;
 *   <li>revision: a change without {@code If-Match} is 428, from a stale revision 409 (ADR-0024);
 *   <li>archive: an archived record leaves the default list and still reads by id;
 *   <li>rights: without {@code view} every path refuses, with {@code view} alone every change is 403, and
 *       {@code form-meta} offers the actions the viewer's rights allow;
 *   <li>scope: a record outside the viewer's scope answers as a missing id, 404, on every path by id, and is in none
 *       of the viewer's lists, bulk actions and exports (ADR-0013);
 *   <li>field rights: a field whose right the viewer lacks is absent everywhere and refused as unknown; a field they
 *       may not write is read-only and refused;
 *   <li>validation: every rule of every written field refuses its invalid value with 422 on the field;
 *   <li>audit: every declared field reaches the history with its label;
 *   <li>export: the export holds the rows of the viewer's list and only the columns the viewer may see.
 * </ul>
 *
 * <pre>{@code
 * class ExampleOrdersContractTest extends EntityContractTestKit {
 *     @Override protected String entity() { return "example.orders"; }
 *     @Override protected EntityTransport transport() { return EntityTransport.module("/api/v1/orders"); }
 * }
 * }</pre>
 *
 * <p>{@code EntityContractCoverageTest} fails the build while an entity with a table has no subclass.
 */
public abstract class EntityContractTestKit extends EmbeddedPostgresTest {

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private EntityRegistry entities;

    /** The code of the entity under test ({@code ms.notes}). */
    protected abstract String entity();

    /**
     * The endpoints of the entity's records: the general runtime by default (ADR-0032, 6.1); a module whose own
     * controller serves the entity names it with {@link EntityTransport#module}.
     */
    protected EntityTransport transport() {
        return EntityTransport.runtime(entity());
    }

    /** What the kit cannot derive from the declaration; nothing by default. */
    protected EntityFixture fixture(FixtureContext context) {
        return EntityFixture.derived();
    }

    @TestFactory
    @DisplayName("ADR-0032, 11: the entity contract")
    protected Stream<DynamicContainer> contract() {
        EntityDefinition definition =
                entities.find(entity()).orElseThrow(() -> new AssertionError("No entity " + entity() + " is declared"));
        long anyUser = jdbc.sql("select id from md_users where login = 'system'")
                .query(Long.class)
                .single();
        EntityFixture fixture = fixture(
                new FixtureContext(jdbc, anyUser, UUID.randomUUID().toString().substring(0, 8)));
        KitWorld world = new KitWorld(wac, jdbc, entities.resolve(definition), transport(), fixture);
        KitCrudChecks crud = new KitCrudChecks(world);
        KitAccessChecks access = new KitAccessChecks(world);
        KitDataChecks data = new KitDataChecks(world);
        return Stream.of(
                        group("metadata", data::metadata),
                        group("CRUD", crud::crud),
                        group("revision (ADR-0024)", crud::revision),
                        group("archive (ADR-0032, 5.4)", crud::archive),
                        group("rights (ADR-0028)", access::rights),
                        group("scope: 404, not 403 (ADR-0013)", access::scope),
                        group("field rights (ADR-0032, 5.2)", new KitFieldRightChecks(world)::fieldRights),
                        group("validation (ADR-0032, 4.2)", data::validation),
                        group("audit (ADR-0017)", data::audit),
                        group("export (ADR-0018)", new KitExportChecks(world)::export))
                .flatMap(Stream::ofNullable);
    }

    /** A group of checks, or null when the declaration gives it nothing to check. */
    private static DynamicContainer group(String name, Supplier<List<DynamicTest>> tests) {
        List<DynamicTest> cases = tests.get();
        return cases.isEmpty() ? null : DynamicContainer.dynamicContainer(name, cases.stream());
    }
}
