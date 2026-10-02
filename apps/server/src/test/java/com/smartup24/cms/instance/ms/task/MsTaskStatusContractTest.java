package com.smartup24.cms.instance.ms.task;

import com.smartup24.cms.instance.ms.task.service.MsTaskStatusEntity;
import com.smartup24.cms.instance.support.entity.EntityContractTestKit;
import com.smartup24.cms.instance.support.entity.EntityFixture;
import com.smartup24.cms.instance.support.entity.FixtureContext;
import java.util.Map;

/**
 * The task statuses pass the entity contract (ADR-0032, 11; plan 10/10, item 5.6) on the general runtime
 * {@code /api/v1/entities/ms.task_statuses}: reference data seen by every holder of the right, archived and deleted,
 * with the order as the record action {@code move}.
 */
class MsTaskStatusContractTest extends EntityContractTestKit {

    @Override
    protected String entity() {
        return MsTaskStatusEntity.CODE;
    }

    /** A colour of the pattern the kit cannot make up. */
    @Override
    protected EntityFixture fixture(FixtureContext context) {
        return EntityFixture.valid(Map.of("color", "#654321"));
    }
}
