package com.smartup24.cms.instance.md;

import com.smartup24.cms.instance.md.service.MdUserEntity;
import com.smartup24.cms.instance.support.entity.EntityContractTestKit;

/**
 * Users pass the entity contract (ADR-0032, 8 and 11; plan 10/10, items 5.6 and 6.2) on the general runtime
 * {@code /api/v1/entities/md.users}: a user whose home and additional units lie outside the viewer's scope is 404 on
 * every path, the same answer as a missing one (ADR-0013); every action — block, unblock, the second factor, the forced
 * password change, anonymisation — needs its right; the role ids exist only for holders of {@code md.assignments.view}
 * and the home unit is written only by holders of {@code md.org_units.assign}.
 */
class MdUserContractTest extends EntityContractTestKit {

    @Override
    protected String entity() {
        return MdUserEntity.CODE;
    }
}
