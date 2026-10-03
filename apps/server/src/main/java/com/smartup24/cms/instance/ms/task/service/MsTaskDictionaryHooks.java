package com.smartup24.cms.instance.ms.task.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.ms.task.repository.MsTaskDictionaryRepository;
import com.smartup24.cms.instance.ms.task.repository.MsTaskDictionaryRepository.Dictionary;
import com.smartup24.cms.platform.api.entity.hook.EntityArchive;
import com.smartup24.cms.platform.api.entity.hook.EntityDelete;
import com.smartup24.cms.platform.api.entity.hook.EntityHooks;
import com.smartup24.cms.platform.api.entity.hook.EntityOperation;
import com.smartup24.cms.platform.api.entity.hook.EntitySave;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What the task types and statuses check besides their declarations (ADR-0032, 6.5): a code in use by another item is
 * refused on the field, a new item without a place goes last, a system item is neither archived nor deleted, and an
 * item tasks still use is not deleted (it is archived instead, so the tasks keep it and no new task takes it).
 */
abstract class MsTaskDictionaryHooks implements EntityHooks {

    private final MsTaskDictionaryRepository dictionaries;
    private final Dictionary dictionary;
    private final Keys keys;

    /** The error keys of one dictionary. */
    record Keys(String codeExists, String systemArchive, String systemDelete, String inUse) {}

    MsTaskDictionaryHooks(MsTaskDictionaryRepository dictionaries, Dictionary dictionary, Keys keys) {
        this.dictionaries = dictionaries;
        this.dictionary = dictionary;
        this.keys = keys;
    }

    /** Whether tasks use the item with this code. */
    abstract boolean inUse(String code);

    /**
     * The code of a new item sent without one, or null when the dictionary requires the code: a status is named by
     * the person, its code is made for it.
     */
    @Nullable
    String generatedCode() {
        return null;
    }

    @Override
    public void beforeSave(EntitySave save) {
        String code = save.values().text("code");
        if (save.operation() == EntityOperation.CREATE && (code == null || code.isBlank())) {
            code = generatedCode();
            if (code != null) save.values().set("code", code);
        }
        long id = save.id() == null ? 0L : save.id();
        if (code != null && save.changed("code") && dictionaries.codeTaken(dictionary, code, id)) {
            save.reject("code", "taken", keys.codeExists(), Map.of());
        }
        if (save.operation() == EntityOperation.CREATE && save.values().get("sortOrder") == null) {
            save.values().set("sortOrder", dictionaries.nextSortOrder(dictionary));
        }
    }

    @Override
    public void beforeArchive(EntityArchive archive) {
        if (archive.archived() && Boolean.TRUE.equals(archive.before().bool("system"))) {
            throw ApiException.conflict(ErrorCode.CONFLICT, keys.systemArchive());
        }
    }

    @Override
    public void beforeDelete(EntityDelete delete) {
        if (Boolean.TRUE.equals(delete.before().bool("system"))) {
            throw ApiException.conflict(ErrorCode.CONFLICT, keys.systemDelete());
        }
        String code = delete.before().text("code");
        if (code != null && inUse(code)) {
            throw ApiException.conflict(ErrorCode.CONFLICT, keys.inUse());
        }
    }
}
