package com.smartup24.cms.instance.example;

import com.smartup24.cms.instance.example.service.ExampleRequestsEntity;
import com.smartup24.cms.instance.md.service.ModuleRegistryService;
import com.smartup24.cms.instance.support.entity.EntityContractTestKit;
import com.smartup24.cms.instance.support.entity.EntityFixture;
import com.smartup24.cms.instance.support.entity.FixtureContext;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The reference document with statuses passes the entity contract (ADR-0032, 9.2 and 11; plan 10/10, item 6.6) on the
 * general runtime {@code /api/v1/entities/example.requests}: each transition from a state it does not leave is 422
 * {@code entity_transition_not_allowed}, without its right 403; a field a state locks is 422 {@code readonly}; the
 * resolution is read-only without {@code approve}. The kit cannot make up a product, so the fixture writes one.
 */
class ExampleRequestsContractTest extends EntityContractTestKit {

    @Autowired
    private ModuleRegistryService modules;

    @BeforeEach
    void switchTheModuleOn() {
        modules.toggleModuleStatus("example", true);
    }

    @Override
    protected String entity() {
        return ExampleRequestsEntity.CODE;
    }

    /** Every request the kit creates names a product of the reference list. */
    @Override
    protected EntityFixture fixture(FixtureContext context) {
        return EntityFixture.valid(Map.of("productId", ExampleProducts.insert(context.jdbc(), context.anyUserId())));
    }
}
