package com.smartup24.cms.instance.ms.task.service;

import com.smartup24.cms.instance.common.entity.hook.EntitySave;
import com.smartup24.cms.instance.ms.task.repository.MsTaskDictionaryRepository;
import com.smartup24.cms.instance.ms.task.repository.MsTaskDictionaryRepository.Dictionary;
import com.smartup24.cms.instance.search.service.SearchChangePublisher;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The hooks of the task statuses (ADR-0032, 8, step 2): see {@link MsTaskDictionaryHooks}; a renamed status also
 * re-indexes the tasks in it, whose search documents carry the status's name.
 */
@Component
public class MsTaskStatusHooks extends MsTaskDictionaryHooks {

    private final MsTaskDictionaryRepository dictionaries;
    private final SearchChangePublisher search;

    public MsTaskStatusHooks(MsTaskDictionaryRepository dictionaries, SearchChangePublisher search) {
        super(
                dictionaries,
                Dictionary.STATUSES,
                new Keys(
                        "error.task.status_code_exists",
                        "error.task.status_system_archive",
                        "error.task.status_system_delete",
                        "error.task.status_in_use"));
        this.dictionaries = dictionaries;
        this.search = search;
    }

    @Override
    public String entity() {
        return MsTaskStatusEntity.CODE;
    }

    @Override
    boolean inUse(String code) {
        return dictionaries.statusInUse(code);
    }

    /** {@code status_} and eight hexadecimal digits: the code pattern, unique enough to be checked like a typed one. */
    @Override
    String generatedCode() {
        return "status_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    @Override
    public void afterSave(EntitySave save) {
        if (save.before() != null && save.changed("name")) {
            search.statusChanged(Objects.requireNonNull(save.id()));
        }
    }
}
