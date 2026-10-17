package com.smartup24.cms.instance.studio.service;

import com.smartup24.cms.instance.common.cluster.ClusterNotices;
import com.smartup24.cms.instance.common.entity.EntityDefinitionSource;
import com.smartup24.cms.instance.common.module.InstalledModules;
import com.smartup24.cms.instance.md.service.MdI18nCatalog;
import com.smartup24.cms.instance.studio.repository.StudioFormRepository;
import com.smartup24.cms.instance.studio.repository.StudioFormRepository.Row;
import com.smartup24.cms.instance.studio.repository.StudioFormRepository.Version;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * The stored live forms as the runtime serves them (ADR-0038). Every read checks the catalog version in the database,
 * so a new or quarantined form is seen by the next request of any node without a notification; the compiled catalog
 * is reused while the version stays the same, and one HTTP request keeps one snapshot for all its consumers.
 *
 * <p>A stored form that does not load — its declaration no longer compiles, its code clashes with a declared entity
 * (the declared one wins) or its labels shadow bundled translation keys, or it lies beyond the form limit — is left
 * out and reported ({@link Catalog#broken()}): the other forms and every declared entity keep working, and the start
 * never fails because of a stored form. A switched-off module serves no live form at all.
 */
@Component
@Profile("!migrate")
public class StudioDefinitions implements EntityDefinitionSource {
    /** The module of the live forms; switched off, it serves none of them. */
    public static final String MODULE = "studio";
    /** The cluster notice that drops the compiled catalog of the other nodes early; correctness never needs it. */
    public static final String NOTICE = "studio.catalog";

    private static final Logger log = LoggerFactory.getLogger(StudioDefinitions.class);

    private final StudioFormRepository repository;
    private final StudioEntityCompiler compiler;
    private final InstalledModules modules;
    private final Supplier<Set<String>> declaredCodes;
    private final Supplier<Set<String>> bundledKeys;
    private final @Nullable ClusterNotices notices;
    private final String snapshotKey = StudioDefinitions.class.getName() + "." + System.identityHashCode(this);
    private volatile @Nullable Catalog cached;
    private volatile @Nullable Set<String> bundled;

    /** Why a stored form is not served; the code is the stored one. */
    public record Broken(String code, String reason) {}

    /**
     * The compiled catalog of one version: what the permission catalog keeps, what is served, what is broken and
     * how many compiled forms are held in quarantine.
     */
    public record Catalog(
            Version version,
            Map<String, Declaration> registered,
            Map<String, Declaration> served,
            List<Broken> broken,
            int quarantined) {}

    /** What one request has read: the catalog and the served view are each taken once, then reused. */
    private static final class Pin {
        private @Nullable Catalog catalog;
        private @Nullable Map<String, Declaration> served;
    }

    @Autowired
    public StudioDefinitions(
            StudioFormRepository repository,
            StudioEntityCompiler compiler,
            InstalledModules modules,
            ObjectProvider<EntityDefinition> declared,
            ObjectProvider<MdI18nCatalog> catalog,
            ObjectProvider<ClusterNotices> notices) {
        this(
                repository,
                compiler,
                modules,
                () -> declared.stream().map(EntityDefinition::code).collect(Collectors.toUnmodifiableSet()),
                () -> keysOf(catalog.getIfAvailable()),
                notices.getIfAvailable());
    }

    public StudioDefinitions(
            StudioFormRepository repository,
            StudioEntityCompiler compiler,
            InstalledModules modules,
            Supplier<Set<String>> declaredCodes,
            Supplier<Set<String>> bundledKeys,
            @Nullable ClusterNotices notices) {
        this.repository = repository;
        this.compiler = compiler;
        this.modules = modules;
        this.declaredCodes = declaredCodes;
        this.bundledKeys = bundledKeys;
        this.notices = notices;
        if (notices != null) notices.onNotice(NOTICE, () -> cached = null);
    }

    /** Every stored form, with the module always on and nothing declared or bundled to clash with. */
    public StudioDefinitions(StudioFormRepository repository, StudioEntityCompiler compiler) {
        this(repository, compiler, module -> true, Set::of, Set::of, null);
    }

    /** The served forms; a switched-off module serves none and its catalog is not read at all. */
    @Override
    public Map<String, Declaration> snapshot() {
        Pin pin = pin();
        Map<String, Declaration> served = pin.served;
        if (served == null) {
            served = active() ? catalog(pin).served() : Map.of();
            pin.served = served;
        }
        return served;
    }

    @Override
    public Map<String, Declaration> registered() {
        return current().registered();
    }

    /** Whether the module of the live forms is switched on. */
    public boolean active() {
        return modules.active(MODULE);
    }

    /** The catalog this request reads; outside a request, the catalog of the current version. */
    public Catalog current() {
        return catalog(pin());
    }

    /** The broken forms of the catalog last compiled on this node, without reading the database. */
    public int lastBroken() {
        Catalog known = cached;
        return known == null ? 0 : known.broken().size();
    }

    /** The quarantined forms of the catalog last compiled on this node, without reading the database. */
    public int lastQuarantined() {
        Catalog known = cached;
        return known == null ? 0 : known.quarantined();
    }

    /** A form was activated, quarantined or released: drop the compiled catalog here and tell the other nodes. */
    public void changed() {
        cached = null;
        if (notices != null) notices.publish(NOTICE);
    }

    private Pin pin() {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (request == null) return new Pin();
        if (request.getAttribute(snapshotKey, RequestAttributes.SCOPE_REQUEST) instanceof Pin pinned) return pinned;
        Pin pin = new Pin();
        request.setAttribute(snapshotKey, pin, RequestAttributes.SCOPE_REQUEST);
        return pin;
    }

    private Catalog catalog(Pin pin) {
        Catalog catalog = pin.catalog;
        if (catalog == null) {
            catalog = fresh();
            pin.catalog = catalog;
        }
        return catalog;
    }

    private Catalog fresh() {
        Catalog known = cached;
        if (known != null && known.version().equals(repository.version())) return known;
        Catalog loaded = compile(repository.catalog());
        cached = loaded;
        return loaded;
    }

    private Catalog compile(StudioFormRepository.Catalog stored) {
        Set<String> codes = declaredCodes.get();
        Set<String> keys = keys();
        Map<String, Declaration> registered = new LinkedHashMap<>();
        Map<String, Declaration> served = new LinkedHashMap<>();
        List<Broken> broken = new ArrayList<>();
        int quarantined = 0;
        List<Row> rows = stored.rows();
        for (int index = 0; index < rows.size(); index++) {
            Row row = rows.get(index);
            if (index >= StudioActivationService.MAX_FORMS) {
                broken.add(report(row, "form_limit", null));
                continue;
            }
            Declaration declaration;
            try {
                declaration = compiler.compile(row.id(), repository.declaration(row.declaration()));
            } catch (RuntimeException failure) {
                // A quarantined form is held on purpose: it is not served, so a failure is not reported again.
                if (row.quarantine() == null) broken.add(report(row, "declaration", failure));
                continue;
            }
            String clash = clash(row, declaration, codes, keys);
            if (clash != null) {
                if (row.quarantine() == null) broken.add(report(row, clash, null));
                continue;
            }
            registered.put(row.code(), declaration);
            if (row.quarantine() == null) served.put(row.code(), declaration);
            else quarantined++;
        }
        return new Catalog(
                stored.version(), Map.copyOf(registered), Map.copyOf(served), List.copyOf(broken), quarantined);
    }

    private static @Nullable String clash(Row row, Declaration declaration, Set<String> codes, Set<String> keys) {
        if (!row.code().equals(declaration.entity().code())) return "code_mismatch";
        if (codes.contains(row.code())) return "declared_code";
        for (Map<String, String> labels : declaration.messages().values()) {
            for (String key : labels.keySet()) {
                if (keys.contains(key)) return "translation_key";
            }
        }
        return null;
    }

    private static Broken report(Row row, String reason, @Nullable RuntimeException failure) {
        log.error(
                "studio_form_broken code={} formId={} reason={} error={}",
                row.code(),
                row.id(),
                reason,
                failure == null ? "-" : failure.getClass().getSimpleName() + ": " + failure.getMessage());
        return new Broken(row.code(), reason);
    }

    private Set<String> keys() {
        Set<String> known = bundled;
        if (known == null) {
            known = Set.copyOf(bundledKeys.get());
            bundled = known;
        }
        return known;
    }

    private static Set<String> keysOf(@Nullable MdI18nCatalog catalog) {
        if (catalog == null) return Set.of();
        Set<String> keys = new HashSet<>(catalog.russianKeys());
        for (String language : catalog.bundledCodes())
            keys.addAll(catalog.bundled(language).keySet());
        return keys;
    }
}
