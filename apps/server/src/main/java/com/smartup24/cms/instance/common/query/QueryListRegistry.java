package com.smartup24.cms.instance.common.query;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The list registry: all {@link QueryList} beans of the application and the lists of its {@link QueryListSource}s
 * (the entities' lists, ADR-0032, 3.4) by code. A list is returned complete: the fields from code take their values
 * as they are now ({@link QueryFieldResolver}, the items of an enumeration, ADR-0032, 4.5) and are joined by
 * extension fields ({@link QueryListExtender}), the entity's custom fields (ADR-0019).
 *
 * <p>A code is declared once: a {@code QueryList} bean with the code of an entity's list fails the start, so an
 * entity's fields cannot be declared a second time for its list (plan 10/10, item 5.1).
 */
@Component
public class QueryListRegistry {

    private final Map<String, QueryList> lists = new TreeMap<>();
    private final List<QueryListExtender> extenders;
    private final List<QueryFieldResolver> resolvers;

    @Autowired
    public QueryListRegistry(
            List<QueryList> declared,
            List<QueryListSource> sources,
            List<QueryListExtender> extenders,
            List<QueryFieldResolver> resolvers) {
        for (QueryList list : declared) {
            add(list);
        }
        for (QueryListSource source : sources) {
            source.lists().forEach(this::add);
        }
        this.extenders = List.copyOf(extenders);
        this.resolvers = List.copyOf(resolvers);
    }

    public QueryListRegistry(
            List<QueryList> declared, List<QueryListSource> sources, List<QueryListExtender> extenders) {
        this(declared, sources, extenders, List.of());
    }

    public QueryListRegistry(List<QueryList> declared) {
        this(declared, List.of(), List.of());
    }

    private void add(QueryList list) {
        if (lists.put(list.code(), list) != null) {
            throw new IllegalStateException("Duplicate query list " + list.code()
                    + ": a list is declared once, an entity's list by its fields only");
        }
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
    public QueryList resolve(QueryList declared) {
        QueryList list = resolvers.isEmpty()
                ? declared
                : declared.withFields(declared.fields().stream()
                        .map(field -> current(declared, field))
                        .toList());
        if (extenders.isEmpty()) return list;
        Set<String> taken = new HashSet<>();
        list.fields().forEach(field -> taken.add(field.key()));
        List<QueryField> extra = extenders.stream()
                .flatMap(extender -> extender.extraFields(list).stream())
                .filter(field -> taken.add(field.key()))
                .toList();
        return list.withExtraFields(extra);
    }

    private QueryField current(QueryList list, QueryField field) {
        QueryField resolved = field;
        for (QueryFieldResolver resolver : resolvers) {
            resolved = resolver.resolve(list, resolved);
        }
        return resolved;
    }
}
