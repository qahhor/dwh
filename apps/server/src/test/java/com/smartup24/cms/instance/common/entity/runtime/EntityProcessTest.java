package com.smartup24.cms.instance.common.entity.runtime;

import static com.smartup24.cms.platform.api.entity.field.EntityFields.select;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.sortable;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.platform.api.entity.Entity;
import com.smartup24.cms.platform.api.entity.EntityCapability;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.EntityScope;
import com.smartup24.cms.platform.api.entity.field.FieldDefault;
import com.smartup24.cms.platform.api.entity.workflow.EntityWorkflow;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ADR-0032, 9.2: a state of the process that is terminal or locks keeps its record — no delete, no archive — and a state
 * that locks nothing does not; an entity without a process is never kept.
 */
class EntityProcessTest {

    private static final EntityDefinition DOCUMENTS = Entity.define("test.documents", "test.documents")
            .table("test_documents", "d")
            .scope(EntityScope.all())
            .field(text("title", "x.title").column("title").list(sortable()))
            .field(select("status", "x.status", List.of("draft", "sent", "closed"), "x.status.")
                    .column("status")
                    .readonly()
                    .defaultValue(FieldDefault.fixed("draft")))
            .section("main", "entity.section.main", "title", "status")
            .actions("create", "update", "delete", "archive")
            .workflow(EntityWorkflow.on("status")
                    .state("draft", "x.draft")
                    .initial()
                    .state("sent", "x.sent")
                    .locks("title")
                    .state("closed", "x.closed")
                    .terminal()
                    .transition("send", "draft", "sent")
                    .transition("close", "sent", "closed")
                    .build())
            .defaultSort("title", Entity.Sort.ASC)
            .capabilities(EntityCapability.ARCHIVE)
            .build();

    @Test
    void aTerminalOrLockingStateKeepsTheRecord() {
        assertThat(EntityProcess.keepingState(DOCUMENTS, Map.of("status", "draft")))
                .isEmpty();
        assertThat(EntityProcess.keepingState(DOCUMENTS, Map.of("status", "sent")))
                .contains("sent");
        assertThat(EntityProcess.keepingState(DOCUMENTS, Map.of("status", "closed")))
                .contains("closed");
        assertThat(EntityProcess.keepingState(DOCUMENTS, Map.of())).isEmpty();
        EntityProcess.requireRemovable(DOCUMENTS, Map.of("status", "draft"), EntityDefinition.DELETE);
    }

    @Test
    void aKeptRecordIsRefusedWithTheStateAndTheAction() {
        assertThatThrownBy(() ->
                        EntityProcess.requireRemovable(DOCUMENTS, Map.of("status", "sent"), EntityDefinition.ARCHIVE))
                .isInstanceOfSatisfying(ApiException.class, refused -> {
                    assertThat(refused.getErrorCode()).isEqualTo(ErrorCode.ENTITY_STATE_LOCKED);
                    assertThat(refused.getMessageKey()).isEqualTo("error.common.entity_state_locked");
                    assertThat(refused.getParams()).isEqualTo(Map.of("state", "sent", "action", "archive"));
                });
        assertThat(ErrorCode.ENTITY_STATE_LOCKED.getDefaultStatus()).isEqualTo(422);
    }

    @Test
    void anEntityWithoutAProcessIsNeverKept() {
        EntityDefinition plain = Entity.define("test.plain", "test.plain")
                .table("test_plain", "p")
                .scope(EntityScope.all())
                .field(text("title", "x.title").column("title").list(sortable()))
                .section("main", "entity.section.main", "title")
                .actions("create", "delete")
                .defaultSort("title", Entity.Sort.ASC)
                .build();
        assertThat(EntityProcess.keepingState(plain, Map.of("title", "closed"))).isEmpty();
    }
}
