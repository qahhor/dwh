package com.acme.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.platform.api.actor.AuditActor;
import com.smartup24.cms.platform.api.entity.hook.EntityDelete;
import com.smartup24.cms.platform.api.entity.hook.EntityRefusal;
import com.smartup24.cms.platform.api.entity.hook.EntityValues;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The hooks of the books, with the API's own types and no platform behind them (ADR-0033, 3.2). */
class LibraryBookHooksTest {

    private final LibraryBookHooks hooks = new LibraryBookHooks();

    private static EntityDelete delete(boolean lent) {
        return new EntityDelete(
                LibraryModule.DEFINITION,
                7L,
                EntityValues.readOnly(LibraryModule.DEFINITION, Map.of("title", "Dune", "lent", lent)),
                AuditActor.user(1L));
    }

    @Test
    void aLentBookIsNotDeleted() {
        assertThat(hooks.entity()).isEqualTo(LibraryModule.BOOKS);
        assertThatThrownBy(() -> hooks.beforeDelete(delete(true)))
                .isInstanceOfSatisfying(EntityRefusal.class, refusal -> {
                    assertThat(refusal.kind()).isEqualTo(EntityRefusal.Kind.CONFLICT);
                    assertThat(refusal.messageKey()).isEqualTo("error.library.book_lent");
                    assertThat(refusal.params()).containsEntry("id", 7L);
                });
        hooks.beforeDelete(delete(false));
    }
}
