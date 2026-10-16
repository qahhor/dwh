package com.smartup24.cms.instance.common.entity;

import com.smartup24.cms.platform.api.entity.EntityDefinition;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** One source snapshot feeds metadata, runtime, lists and translated labels (ADR-0038). */
@Component
public class LiveEntities {
    private final Supplier<List<EntityDefinitionSource>> sources;

    @Autowired
    public LiveEntities(ObjectProvider<EntityDefinitionSource> sources) {
        this(() -> sources.orderedStream().toList());
    }

    public LiveEntities(Supplier<List<EntityDefinitionSource>> sources) {
        this.sources = sources;
    }

    public static LiveEntities empty() {
        return new LiveEntities(List::of);
    }

    public Map<String, EntityDefinitionSource.Declaration> declarations() {
        Map<String, EntityDefinitionSource.Declaration> result = new LinkedHashMap<>();
        for (EntityDefinitionSource source : sources.get()) {
            source.snapshot().forEach((code, declaration) -> {
                if (!code.equals(declaration.entity().code()) || result.putIfAbsent(code, declaration) != null) {
                    throw new IllegalStateException("Duplicate or mismatched live entity " + code);
                }
            });
        }
        return Map.copyOf(result);
    }

    public List<EntityDefinition> all() {
        return declarations().values().stream()
                .map(EntityDefinitionSource.Declaration::entity)
                .toList();
    }

    public Optional<EntityDefinition> find(String code) {
        return Optional.ofNullable(declarations().get(code)).map(EntityDefinitionSource.Declaration::entity);
    }

    public Map<String, String> messages(String language, Predicate<EntityDefinition> visible) {
        Map<String, String> result = new LinkedHashMap<>();
        for (var declaration : declarations().values()) {
            if (!visible.test(declaration.entity())) continue;
            Map<String, String> names = declaration
                    .messages()
                    .getOrDefault(language, declaration.messages().getOrDefault("ru", Map.of()));
            names.forEach((key, value) -> {
                if (result.putIfAbsent(key, value) != null)
                    throw new IllegalStateException("Duplicate live label " + key);
            });
        }
        return Map.copyOf(result);
    }
}
