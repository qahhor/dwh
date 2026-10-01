package com.smartup24.cms.instance.ms.note;

import com.smartup24.cms.instance.ms.note.service.MsNoteEntity;
import com.smartup24.cms.instance.support.entity.EntityContractTestKit;

/**
 * Notes pass the entity contract (ADR-0032, 11; plan 10/10, items 5.4 and 6.2) on the general runtime
 * {@code /api/v1/entities/ms.notes}: a note is its owner's alone, so another person's note is 404 on every path, the
 * same answer as a missing one, and an entity the viewer may not see is 404 too.
 */
class MsNoteContractTest extends EntityContractTestKit {

    @Override
    protected String entity() {
        return MsNoteEntity.CODE;
    }
}
