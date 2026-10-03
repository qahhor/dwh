package com.acme.library;

import com.smartup24.cms.instance.support.entity.EntityContractTestKit;
import com.smartup24.cms.instance.support.entity.EntityFixture;
import com.smartup24.cms.instance.support.entity.FixtureContext;
import java.util.Map;

/**
 * Plan 10/10, items 6.2 and 6.3 (ADR-0033, 8): a module outside the monorepo passes the entity contract by one
 * subclass of the published test kit. The kit starts the platform with this module's jar on the classpath: the
 * manifest is checked, the configuration imported, the migration applied, the registry row and the rights created,
 * and the general runtime serves the books.
 */
class LibraryBooksContractTest extends EntityContractTestKit {

    @Override
    protected String entity() {
        return LibraryModule.BOOKS;
    }

    @Override
    protected EntityFixture fixture(FixtureContext context) {
        // A lent book is not deleted (LibraryBookHooks): the kit's books stay on the shelf.
        return EntityFixture.valid(Map.of(
                        "title", "Dune " + context.tag(), "isbn", "9780441013593", "pages", "412", "lent", false))
                .update(Map.of("title", "Dune Messiah " + context.tag()));
    }
}
