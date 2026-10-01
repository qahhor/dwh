package com.smartup24.cms.instance.ms.note;

import com.smartup24.cms.instance.ms.note.service.MsNoteEntity;
import com.smartup24.cms.instance.support.entity.EntityContractTestKit;
import com.smartup24.cms.instance.support.entity.EntityTransport;
import java.util.Map;
import org.springframework.http.HttpMethod;

/**
 * Notes pass the entity contract (ADR-0032, 11; plan 10/10, item 6.2) through their own controller until the general
 * runtime serves them (plan 10/10, item 5.4): a note is its owner's alone, so another person's note is 404 on every
 * path, the same answer as a missing one.
 */
class MsNoteContractTest extends EntityContractTestKit {

    @Override
    protected String entity() {
        return MsNoteEntity.CODE;
    }

    @Override
    protected EntityTransport transport() {
        return EntityTransport.module("/api/v1/notes")
                .action("pin", HttpMethod.PUT, id -> "/api/v1/notes/" + id + "/pin", Map.of("pinned", true));
    }
}
