package com.greenwhite.dwh.instance.common.query;

import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Реестр списков: все бины {@link QueryList} приложения по коду. Список отдаётся уже достроенным: к полям из
 * кода добавляются поля расширений ({@link QueryListExtender}) — дополнительные поля сущности (ADR-0019, 2.3).
 */
@Component
public class QueryListRegistry {

    private final Map<String, QueryList> lists = new TreeMap<>();
    private final List<QueryListExtender> extenders;

    @org.springframework.beans.factory.annotation.Autowired
    public QueryListRegistry(List<QueryList> declared, List<QueryListExtender> extenders) {
        for (QueryList list : declared) {
            if (lists.put(list.code(), list) != null) {
                throw new IllegalStateException("Duplicate query list " + list.code());
            }
        }
        this.extenders = List.copyOf(extenders);
    }

    public QueryListRegistry(List<QueryList> declared) {
        this(declared, List.of());
    }

    public Optional<QueryList> find(String code) {
        return Optional.ofNullable(lists.get(code)).map(this::resolve);
    }

    public QueryList get(String code) {
        return find(code).orElseThrow(() -> new IllegalStateException("Unknown query list " + code));
    }

    /**
     * The list with its extra fields as they are now. An extra field whose key a declared field already uses is
     * left out, so an administrator's field can never shadow one the module relies on.
     */
    public QueryList resolve(QueryList list) {
        if (extenders.isEmpty()) return list;
        Set<String> taken = new HashSet<>();
        list.fields().forEach(field -> taken.add(field.key()));
        List<QueryField> extra = extenders.stream()
                .flatMap(extender -> extender.extraFields(list).stream())
                .filter(field -> taken.add(field.key()))
                .toList();
        return list.withExtraFields(extra);
    }
}
