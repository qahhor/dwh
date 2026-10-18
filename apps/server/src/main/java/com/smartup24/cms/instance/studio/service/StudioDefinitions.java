package com.smartup24.cms.instance.studio.service;

import com.smartup24.cms.instance.common.cluster.ClusterNotices;
import com.smartup24.cms.instance.common.entity.EntityDefinitionSource;
import com.smartup24.cms.instance.common.module.InstalledModules;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Labels;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
 *
 * <p>Every stored form keeps its rights in the permission catalog ({@link #registered()}), served or not: a form that
 * does not compile is registered from its stored code alone, so a restart never deprecates the rights of a broken or
 * quarantined form (ADR-0038, decision 10).
 *
 * <p>A form serves the declaration of its active version; an archived form serves read-only, keeps its rights and does
 * not count toward the limit of active forms (ADR-0041). The quarantine wins over the archive: a held form is not served
 * at all.
 */
@Component
@Profile("!migrate")
public class StudioDefinitions implements EntityDefinitionSource {
    /** The module of the live forms; switched off, it serves none of them. */
    public static final String MODULE = "studio";
    /** The cluster notice that drops the compiled catalog of the other nodes early; correctness never needs it. */
    public static final String NOTICE = "studio.catalog";

    private static final String PREFIX = MODULE + ".";

    private static final Logger log = LoggerFactory.getLogger(StudioDefinitions.class);

    private final StudioFormRepository repository;
    private final StudioEntityCompiler compiler;
    private final InstalledModules modules;
    private final Supplier<Set<String>> declaredCodes;
    private final Supplier<Set<String>> bundledKeys;
    private final @Nullable ClusterNotices notices;
    private final String snapshotKey = StudioDefinitions.class.getName() + "." + System.identityHashCode(this);
    private volatile @Nullable Catalog cached;
    /** The catalog last compiled on this node, kept for the meters until the next compilation replaces it. */
    private volatile @Nullable Catalog lastCompiled;

    private volatile @Nullable Set<String> bundled;

    /** Why a stored form is not served; the code is the stored one. */
    public record Broken(String code, String reason) {}

    /**
     * The compiled catalog of one version: what the permission catalog keeps, what is served, which served forms are
     * archived (read-only), what is broken and how many stored forms are held in quarantine (every quarantine row,
     * compiled or not).
     */
    public record Catalog(
            Version version,
            Map<String, Declaration> registered,
            Map<String, Declaration> served,
            Set<String> archived,
            List<Broken> broken,
            int quarantined) {
        public Catalog(
                Version version,
                Map<String, Declaration> registered,
                Map<String, Declaration> served,
                List<Broken> broken,
                int quarantined) {
            this(version, registered, served, Set.of(), broken, quarantined);
        }
    }

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
        if (notices != null) notices.onNotice(NOTICE, this::reload);
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

    /**
     * The broken forms of the catalog last compiled on this node, without reading the database; a change of the
     * catalog keeps the last value until the catalog is compiled again. Zero while the module is switched off.
     */
    public int lastBroken() {
        Catalog known = lastCompiled;
        return known == null || !active() ? 0 : known.broken().size();
    }

    /** The quarantined forms of the catalog last compiled on this node, like {@link #lastBroken()}. */
    public int lastQuarantined() {
        Catalog known = lastCompiled;
        return known == null || !active() ? 0 : known.quarantined();
    }

    /** Whether the served form {@code code} is archived: its records are read-only (ADR-0041). */
    public boolean archived(String code) {
        return active() && current().archived().contains(code);
    }

    /**
     * A form was activated, quarantined or released, got a new version, was archived or restored. The notice to the other nodes leaves with the commit (the
     * {@link ClusterNotices#publish} contract: PostgreSQL delivers it at the commit and drops it with a rollback), and
     * the copy of this node is dropped after the commit; the version check keeps every read correct meanwhile.
     */
    public void changed() {
        if (notices != null) notices.publish(NOTICE);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cached = null;
                }
            });
        } else {
            cached = null;
        }
    }

    /**
     * Why a new form would not load, checked by the activation before it stores the form: {@code declared_code} or
     * {@code translation_key}, or null when it would load (ADR-0038, decision 9).
     */
    public @Nullable String refusal(String code, Declaration compiled) {
        return clash(code, compiled, declaredCodes.get(), keys());
    }

    /**
     * Another node changed the catalog: drop the copy and, while the module is on, compile the catalog again at once,
     * so the meters of this node follow the change without waiting for a request.
     */
    private void reload() {
        cached = null;
        if (!active()) return;
        try {
            fresh();
        } catch (RuntimeException failure) {
            log.warn("studio_catalog_reload_failed error={}", failure.toString());
        }
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
        lastCompiled = loaded;
        return loaded;
    }

    private Catalog compile(StudioFormRepository.Catalog stored) {
        Set<String> codes = declaredCodes.get();
        Set<String> keys = keys();
        Map<String, Declaration> registered = new LinkedHashMap<>();
        Map<String, Declaration> served = new LinkedHashMap<>();
        Set<String> archived = new HashSet<>();
        List<Broken> broken = new ArrayList<>();
        List<Row> rows = stored.rows();
        StudioSources sources = StudioSources.read(repository, rows);
        int active = 0;
        for (int index = 0; index < rows.size(); index++) {
            Row row = rows.get(index);
            if (!row.archived()) active++;
            if (index >= StudioFormRepository.MAX_STORED || active > StudioActivationService.MAX_FORMS) {
                broken.add(report(row, "form_limit", null));
                rightsOnly(row, null, keys, registered);
                continue;
            }
            MdSimpleFormDtos.Declaration source = null;
            Declaration declaration;
            try {
                source = sources.read(row);
                declaration = compiler.compile(row.id(), source, row.archived(), sources::title);
            } catch (RuntimeException failure) {
                // A quarantined form is held on purpose: it is not served, so a failure is not reported again.
                if (row.quarantine() == null) broken.add(report(row, "declaration", failure));
                rightsOnly(row, source, keys, registered);
                continue;
            }
            String clash = clash(row.code(), declaration, codes, keys);
            if (clash != null) {
                if (row.quarantine() == null) broken.add(report(row, clash, null));
                rightsOnly(row, source, keys, registered);
                continue;
            }
            // An archived form compiles without its write actions; its rights stay those of every form.
            if (row.archived()) rightsOnly(row, source, keys, registered);
            else registered.put(row.code(), declaration);
            if (row.quarantine() == null) {
                served.put(row.code(), declaration);
                if (row.archived()) archived.add(row.code());
            }
        }
        // Every quarantine row counts, compiled or not, as the catalog view lists them.
        return new Catalog(
                stored.version(),
                Map.copyOf(registered),
                Map.copyOf(served),
                Set.copyOf(archived),
                List.copyOf(broken),
                (int) stored.version().held());
    }

    /**
     * The rights of a stored form that is not served as it is, derived from its stored code without compiling its
     * declaration: the pairs the compiler registers for every form, named by the stored title when it still reads. A
     * label that shadows a bundled key is left out, so it never names a bundled right.
     */
    private void rightsOnly(
            Row row,
            MdSimpleFormDtos.@Nullable Declaration source,
            Set<String> keys,
            Map<String, Declaration> registered) {
        if (!row.code().startsWith(PREFIX)) return;
        Labels title = source == null || source.entity() == null
                ? null
                : source.entity().title();
        Declaration rights;
        try {
            rights = compiler.rights(row.id(), row.code().substring(PREFIX.length()), title);
        } catch (RuntimeException failure) {
            log.error(
                    "studio_form_rights_skipped code={} formId={} error={}", row.code(), row.id(), failure.toString());
            return;
        }
        Map<String, Map<String, String>> messages = new LinkedHashMap<>();
        rights.messages().forEach((language, labels) -> {
            Map<String, String> kept = new LinkedHashMap<>(labels);
            kept.keySet().removeAll(keys);
            messages.put(language, kept);
        });
        registered.putIfAbsent(row.code(), new Declaration(rights.entity(), messages));
    }

    private static @Nullable String clash(String code, Declaration declaration, Set<String> codes, Set<String> keys) {
        if (!code.equals(declaration.entity().code())) return "code_mismatch";
        if (codes.contains(code)) return "declared_code";
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
