package com.smartup24.cms.instance.ms.task;

import com.smartup24.cms.instance.ms.task.service.MsTaskEntity;
import com.smartup24.cms.instance.support.entity.EntityContractTestKit;
import com.smartup24.cms.instance.support.entity.EntityFixture;
import com.smartup24.cms.instance.support.entity.FixtureContext;
import java.util.Map;

/**
 * Tasks pass the entity contract (ADR-0032, 11; plan 10/10, item 5.6) on the general runtime
 * {@code /api/v1/entities/ms.tasks}: a task of a person in another org unit — in which the viewer takes no part — is
 * outside the viewer's scope (ADR-0013) and answers 404 on every path, the same as a missing one.
 */
class MsTaskContractTest extends EntityContractTestKit {

    @Override
    protected String entity() {
        return MsTaskEntity.CODE;
    }

    /** A type of the reference list the kit cannot make up. */
    @Override
    protected EntityFixture fixture(FixtureContext context) {
        return EntityFixture.valid(Map.of("typeCode", "task"));
    }
}
