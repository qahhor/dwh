package com.greenwhite.dwh.instance.common.query;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Реестр списков: все бины {@link QueryList} приложения по коду. */
@Component
public class QueryListRegistry {

    private final Map<String, QueryList> lists = new TreeMap<>();

    public QueryListRegistry(List<QueryList> declared) {
        for (QueryList list : declared) {
            if (lists.put(list.code(), list) != null) {
                throw new IllegalStateException("Duplicate query list " + list.code());
            }
        }
    }

    public Optional<QueryList> find(String code) {
        return Optional.ofNullable(lists.get(code));
    }

    public QueryList get(String code) {
        return find(code).orElseThrow(() -> new IllegalStateException("Unknown query list " + code));
    }
}
