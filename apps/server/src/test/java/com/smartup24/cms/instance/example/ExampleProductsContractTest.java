package com.smartup24.cms.instance.example;

import com.smartup24.cms.instance.example.service.ExampleProductsEntity;
import com.smartup24.cms.instance.md.service.ModuleRegistryService;
import com.smartup24.cms.instance.support.entity.EntityContractTestKit;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The reference list passes the entity contract (ADR-0032, 11; plan 10/10, item 6.6) on the general runtime
 * {@code /api/v1/entities/example.products}: the kit derives every case — the archive, the import by code, the field
 * rules, the scope — from the declaration alone, so the fixture is empty. The module ships switched off (ADR-0032, 19,
 * question 3), so the test switches it on first.
 */
class ExampleProductsContractTest extends EntityContractTestKit {

    @Autowired
    private ModuleRegistryService modules;

    @BeforeEach
    void switchTheModuleOn() {
        modules.toggleModuleStatus("example", true);
    }

    @Override
    protected String entity() {
        return ExampleProductsEntity.CODE;
    }
}
