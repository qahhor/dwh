package com.smartup24.cms.instance.ms.task.service;

import com.smartup24.cms.instance.ms.task.repository.MsTaskDictionaryRepository;
import com.smartup24.cms.instance.ms.task.repository.MsTaskDictionaryRepository.Dictionary;
import org.springframework.stereotype.Component;

/** The hooks of the task types (ADR-0032, 8, step 1): see {@link MsTaskDictionaryHooks}. */
@Component
public class MsTaskTypeHooks extends MsTaskDictionaryHooks {

    private final MsTaskDictionaryRepository dictionaries;

    public MsTaskTypeHooks(MsTaskDictionaryRepository dictionaries) {
        super(
                dictionaries,
                Dictionary.TYPES,
                new Keys(
                        "error.task.type_code_exists",
                        "error.task.type_system_archive",
                        "error.task.type_system_delete",
                        "error.task.type_in_use"));
        this.dictionaries = dictionaries;
    }

    @Override
    public String entity() {
        return MsTaskTypeEntity.CODE;
    }

    @Override
    boolean inUse(String code) {
        return dictionaries.typeInUse(code);
    }
}
