package com.smartup24.cms.instance.ms.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.ms.task.pref.MsTaskPref;
import com.smartup24.cms.instance.ms.task.service.MsTaskEntity;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.field.FieldAccess;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR-0032, 5.2: a task's list field that reads the project's table around the projects' scope exists only for a
 * viewer who holds the projects' {@code view} right; the kit's field-rights checks then prove it absent everywhere for
 * anyone else ({@code MsTaskContractTest}).
 */
class MsTaskEntityRightsTest {

    @Test
    @DisplayName("projectName needs tasks.projects:view")
    void theProjectNameNeedsTheProjectsRight() {
        EntityDefinition tasks = MsTaskEntity.definition((userId, alias) -> {
            throw new IllegalStateException("no rows are read here");
        });

        FieldAccess access = tasks.model().field("projectName").orElseThrow().access();

        assertThat(access.requiredForm()).isEqualTo(MsTaskPref.FORM_PROJECTS);
        assertThat(access.requiredAction()).isEqualTo("view");
    }
}
