package com.smartup24.cms.instance.ms.task.service;

import com.smartup24.cms.instance.ms.task.repository.MsTaskDictionaryRepository;
import com.smartup24.cms.instance.ms.task.repository.MsTaskDictionaryRepository.Dictionary;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The hooks of the task statuses (ADR-0032, 8, step 2): see {@link MsTaskDictionaryHooks}.
 */
@Component
public class MsTaskStatusHooks extends MsTaskDictionaryHooks {

    private final MsTaskDictionaryRepository dictionaries;

    public MsTaskStatusHooks(MsTaskDictionaryRepository dictionaries) {
        super(
                dictionaries,
                Dictionary.STATUSES,
                new Keys(
                        "error.task.status_code_exists",
                        "error.task.status_system_archive",
                        "error.task.status_system_delete",
                        "error.task.status_in_use"));
        this.dictionaries = dictionaries;
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
}
