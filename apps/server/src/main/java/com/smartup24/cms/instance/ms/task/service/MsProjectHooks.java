package com.smartup24.cms.instance.ms.task.service;

import com.smartup24.cms.instance.common.entity.hook.EntityHooks;
import com.smartup24.cms.instance.common.entity.hook.EntitySave;
import com.smartup24.cms.instance.ms.task.repository.MsProjectRepository;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The hooks of the projects (ADR-0032, 8, step 3): a name another project in use has is refused on the field. The
 * search indexes a saved, archived or restored project from its change event (ADR-0032, 10.3).
 */
@Component
public class MsProjectHooks implements EntityHooks {

    private final MsProjectRepository projects;

    public MsProjectHooks(MsProjectRepository projects) {
        this.projects = projects;
    }

    @Override
    public String entity() {
        return MsProjectEntity.CODE;
    }

    @Override
    public void beforeSave(EntitySave save) {
        String name = save.values().text("name");
        long id = save.id() == null ? 0L : save.id();
        if (name != null && save.changed("name") && projects.nameTaken(name, id)) {
            save.reject("name", "taken", "error.project.name_exists", Map.of());
        }
    }
}
