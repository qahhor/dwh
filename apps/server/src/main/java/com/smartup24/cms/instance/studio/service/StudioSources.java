package com.smartup24.cms.instance.studio.service;

import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Declaration;
import com.smartup24.cms.instance.studio.repository.StudioFormRepository;
import com.smartup24.cms.instance.studio.repository.StudioFormRepository.Row;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The stored declarations of a catalog as they read, each failure kept for its form, and the key naming the records of
 * each form that reads ({@link StudioEntityCompiler#titleKey}): a reference to a live form names its rows by it
 * (ADR-0041), so the catalog reads every form before it compiles one.
 */
record StudioSources(Map<Long, Declaration> parsed, Map<Long, RuntimeException> unread, Map<String, String> titles) {

    static StudioSources read(StudioFormRepository repository, List<Row> rows) {
        Map<Long, Declaration> parsed = new HashMap<>();
        Map<Long, RuntimeException> unread = new HashMap<>();
        Map<String, String> titles = new HashMap<>();
        for (Row row : rows) {
            try {
                Declaration source = repository.declaration(row.declaration());
                parsed.put(row.id(), source);
                titles.put(row.code(), StudioEntityCompiler.titleKey(source));
            } catch (RuntimeException failure) {
                // Reported with its form when the catalog compiles it.
                unread.put(row.id(), failure);
            }
        }
        return new StudioSources(parsed, unread, titles);
    }

    /** The declaration of {@code row}; the failure that kept it from reading otherwise. */
    Declaration read(Row row) {
        Declaration source = parsed.get(row.id());
        if (source == null) throw Objects.requireNonNull(unread.get(row.id()), "unread");
        return source;
    }

    /** The key naming the records of the live form {@code code}; a form that does not read is named by its name. */
    String title(String code) {
        return titles.getOrDefault(code, StudioFormLayout.NAME);
    }
}
