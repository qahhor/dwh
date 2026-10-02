package com.smartup24.cms.instance.example;

import com.smartup24.cms.instance.example.service.ExampleOrderEntity;
import com.smartup24.cms.instance.md.service.ModuleRegistryService;
import com.smartup24.cms.instance.support.entity.EntityContractTestKit;
import com.smartup24.cms.instance.support.entity.EntityFixture;
import com.smartup24.cms.instance.support.entity.FixtureContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The reference document passes the entity contract (ADR-0032, 9.4 and 11; plan 10/10, item 5.7) on the general runtime
 * {@code /api/v1/entities/example.orders}, the checks of its lines and of its process included: a line's mistake is
 * addressed {@code lines[i].field}, a transition from a state it does not leave is 422
 * {@code entity_transition_not_allowed}, a transition without its right 403, a field a state locks 422
 * {@code readonly}. The module ships switched off (ADR-0032, 19, question 3), so the test switches it on first.
 */
class ExampleOrderContractTest extends EntityContractTestKit {

    @Autowired
    private ModuleRegistryService modules;

    @BeforeEach
    void switchTheModuleOn() {
        modules.toggleModuleStatus("example", true);
    }

    @Override
    protected String entity() {
        return ExampleOrderEntity.CODE;
    }

    /** Every order the kit creates has two lines, so it can be posted. */
    @Override
    protected EntityFixture fixture(FixtureContext context) {
        return EntityFixture.valid(Map.of(
                        ExampleOrderEntity.LINES,
                        List.of(
                                Map.of("product", "Flour " + context.tag(), "qty", "3", "price", "10.00"),
                                Map.of("product", "Sugar " + context.tag(), "qty", "1.5", "price", "4.20"))))
                .update(Map.of("comment", "changed " + context.tag()));
    }
}
