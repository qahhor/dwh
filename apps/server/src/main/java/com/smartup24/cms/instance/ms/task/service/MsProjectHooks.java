package com.smartup24.cms.instance.ms.task.service;

import com.smartup24.cms.instance.common.entity.hook.EntityArchive;
import com.smartup24.cms.instance.common.entity.hook.EntityHooks;
import com.smartup24.cms.instance.common.entity.hook.EntitySave;
import com.smartup24.cms.instance.ms.task.repository.MsProjectRepository;
import com.smartup24.cms.instance.search.service.SearchChangePublisher;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * The hooks of the projects (ADR-0032, 8, step 3): a name another project in use has is refused on the field; a saved,
 * archived or restored project — with the tasks that show its name — is indexed again for the search, in the
 * transaction of the change (the search's outbox commits with it).
 */
@Component
public class MsProjectHooks implements EntityHooks {

    private final MsProjectRepository projects;
    private final SearchChangePublisher search;

    public MsProjectHooks(MsProjectRepository projects, SearchChangePublisher search) {
        this.projects = projects;
        this.search = search;
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

    @Override
    public void afterSave(EntitySave save) {
        search.projectChanged(Objects.requireNonNull(save.id()));
    }

    @Override
    public void beforeArchive(EntityArchive archive) {
        search.projectChanged(archive.id());
    }
}
