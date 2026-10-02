package com.smartup24.cms.instance.ms.task;

import com.smartup24.cms.instance.ms.task.service.MsProjectEntity;
import com.smartup24.cms.instance.support.entity.EntityContractTestKit;

/**
 * Projects pass the entity contract (ADR-0032, 11; plan 10/10, item 5.6) on the general runtime
 * {@code /api/v1/entities/ms.projects}: a project of a person in another org unit — with no member and no task of
 * the viewer's — is outside the viewer's scope (ADR-0013) and answers 404 on every path, the same as a missing one.
 */
class MsProjectContractTest extends EntityContractTestKit {

    @Override
    protected String entity() {
        return MsProjectEntity.CODE;
    }
}
