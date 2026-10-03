package com.acme.library;

import com.smartup24.cms.platform.api.entity.hook.EntityDelete;
import com.smartup24.cms.platform.api.entity.hook.EntityHooks;
import com.smartup24.cms.platform.api.entity.hook.EntityRefusal;
import com.smartup24.cms.platform.api.entity.hook.EntitySave;
import java.util.Map;

/**
 * What the books declaration cannot say (ADR-0032, 6.5): an ISBN is kept without its dashes, and a lent book is not
 * deleted — the refusal of a module outside the monorepo is an {@link EntityRefusal} (ADR-0033, 3.2).
 */
public class LibraryBookHooks implements EntityHooks {

    @Override
    public String entity() {
        return LibraryModule.BOOKS;
    }

    @Override
    public void beforeSave(EntitySave save) {
        String isbn = save.values().text("isbn");
        if (isbn != null && isbn.contains("-")) {
            save.values().set("isbn", isbn.replace("-", ""));
        }
    }

    @Override
    public void beforeDelete(EntityDelete delete) {
        if (Boolean.TRUE.equals(delete.before().bool("lent"))) {
            throw EntityRefusal.conflict("error.library.book_lent", Map.of("id", delete.id()));
        }
    }
}
