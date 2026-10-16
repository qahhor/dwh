package com.smartup24.cms.instance.md.service;

import com.smartup24.cms.instance.common.entity.EntityRegistry;
import com.smartup24.cms.instance.common.entity.LiveEntities;
import com.smartup24.cms.instance.md.api.MdRoleDtos.FormCatalogItem;
import com.smartup24.cms.instance.md.pref.MdFormCatalog;
import com.smartup24.cms.instance.md.repository.MdPermissionRepository;
import com.smartup24.cms.platform.api.entity.EntityCapability;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.EntityDefinition.EntityRights;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MdPermissionService {

    private final MdPermissionRepository permissionRepository;
    /** Declared entities name their own rights (roadmap item 57); null — only the catalog names them. */
    private final EntityRegistry entities;
    /** The Russian catalog names each right in the stored catalog (ADR-0031); null — the key itself. */
    private final MdI18nCatalog catalog;

    private final LiveEntities live;

    @Autowired
    public MdPermissionService(
            MdPermissionRepository permissionRepository,
            @Lazy EntityRegistry entities,
            MdI18nCatalog catalog,
            LiveEntities live) {
        this.permissionRepository = permissionRepository;
        this.entities = entities;
        this.catalog = catalog;
        this.live = live;
    }

    public MdPermissionService(
            MdPermissionRepository permissionRepository, EntityRegistry entities, MdI18nCatalog catalog) {
        this(permissionRepository, entities, catalog, LiveEntities.empty());
    }

    public MdPermissionService(MdPermissionRepository permissionRepository) {
        this(permissionRepository, null, null);
    }

    @Transactional(readOnly = true)
    public Set<String> getEffectivePermissions(Long userId) {
        return permissionRepository.getEffectivePermissionsForUser(userId);
    }

    @Transactional(readOnly = true)
    public long getPermissionVersion(Long userId) {
        return permissionRepository.getPermissionVersion(userId);
    }

    @Transactional
    public void recalculateEffectivePermissions(Long userId) {
        permissionRepository.recalculateEffectivePermissions(userId);
    }

    @Transactional(readOnly = true)
    public List<MdPermissionRepository.FormTreeItem> getFormCatalog() {
        return permissionRepository.getAllFormsWithActions();
    }

    /** The catalog as the role editor reads it. */
    @Transactional(readOnly = true)
    public List<FormCatalogItem> getFormCatalogItems() {
        return getFormCatalog().stream()
                .map(i -> {
                    Optional<EntityRights> rights = rightsOf(i.formCode());
                    var form = MdFormCatalog.find(i.formCode());
                    return new FormCatalogItem(
                            i.formCode(),
                            i.module(),
                            i.formName(),
                            rights.map(EntityRights::nameKey)
                                    .orElseGet(() -> form.map(MdFormCatalog.FormMeta::nameKey)
                                            .orElse(null)),
                            i.action(),
                            i.actionName(),
                            rights.map(named -> named.actionKeys().get(i.action()))
                                    .orElseGet(() -> form.map(
                                                    named -> named.actionKeys().get(i.action()))
                                            .orElse(null)),
                            i.isDeprecated());
                })
                .toList();
    }

    private Optional<EntityRights> rightsOf(String formCode) {
        return entities == null ? Optional.empty() : entities.rights(formCode);
    }

    /** The Russian words of a key for the stored catalog, the source language (ADR-0031); the key when absent. */
    private String russian(String key) {
        if (key == null) return null;
        return catalog == null ? key : catalog.bundled("ru").getOrDefault(key, key);
    }

    /**
     * Brings the form catalog in line with the code (FR-PERM-1).
     *
     * A pair's existence is defined by {@code @RequiresPermission} annotations,
     * names by the entity declaration ({@link EntityRights}), and for forms without one by
     * the {@link MdFormCatalog} directory. Everything not among the
     * declared pairs is marked obsolete but not deleted: deletion
     * would cascade to permissions already granted.
     *
     * @param declaredPairs {@code form.action} pairs found in the code
     */
    @Transactional
    public CatalogSyncResult syncFormCatalog(Set<String> declaredPairs) {
        permissionRepository.lockCatalog();
        Set<String> livePairs = new TreeSet<>(declaredPairs);
        List<EntityDefinition> snapshot = entities == null ? List.of() : entities.all();
        livePairs.addAll(MdFormCatalogSynchronizer.entityPairs(snapshot));
        Map<String, EntityRights> namedRights = new LinkedHashMap<>();
        for (EntityDefinition entity : snapshot) {
            if (entity.rights() != null) namedRights.putIfAbsent(entity.form(), entity.rights());
        }
        Map<String, String> liveNames = live.messages("ru", entity -> true);
        Set<String> beforeGrantable = permissionRepository.getGrantablePairs();

        Set<String> registeredForms = new TreeSet<>();
        for (String pair : livePairs) {
            int dot = pair.lastIndexOf('.');
            String formCode = pair.substring(0, dot);
            String action = pair.substring(dot + 1);

            Optional<EntityRights> rights = Optional.ofNullable(namedRights.get(formCode));
            if (registeredForms.add(formCode)) {
                permissionRepository.registerForm(
                        formCode,
                        rights.map(EntityRights::module).orElseGet(() -> MdFormCatalog.moduleOf(formCode)),
                        named(
                                liveNames,
                                rights.map(EntityRights::nameKey)
                                        .orElseGet(() -> MdFormCatalog.formNameKeyOf(formCode))));
            }
            permissionRepository.registerFormAction(
                    formCode,
                    action,
                    named(
                            liveNames,
                            rights.map(named -> named.actionKeys().get(action))
                                    .orElseGet(() -> MdFormCatalog.actionNameKeyOf(formCode, action))));
        }

        int deprecated = permissionRepository.deprecateMissing(livePairs);

        List<String> deprecatedPairs = beforeGrantable.stream()
                .filter(pair -> !livePairs.contains(pair))
                .sorted()
                .toList();

        return new CatalogSyncResult(livePairs.size(), deprecated, deprecatedPairs);
    }

    /** Registers a live entity in the activation transaction and refreshes the affected users (ADR-0038). */
    @Transactional
    public void registerEntityDefinition(EntityDefinition entity, Map<String, String> russianLabels) {
        permissionRepository.lockCatalog();
        EntityRights rights = Objects.requireNonNull(entity.rights(), "Live entity rights");
        permissionRepository.registerForm(entity.form(), rights.module(), named(russianLabels, rights.nameKey()));
        Set<String> actions = new TreeSet<>();
        actions.add("view");
        entity.actions().forEach(action -> actions.add(action.permission()));
        if (entity.capabilities().contains(EntityCapability.IMPORT)) actions.add(EntityDefinition.IMPORT);
        for (String action : actions) {
            String key = Objects.requireNonNull(rights.actionKeys().get(action), "Live entity action name");
            permissionRepository.registerFormAction(entity.form(), action, named(russianLabels, key));
        }
        for (Long user : permissionRepository.grantEntityToAdministrativeRoles(entity.form(), actions)) {
            permissionRepository.recalculateEffectivePermissions(user);
        }
    }

    private String named(Map<String, String> names, String key) {
        return key != null && names.containsKey(key) ? names.get(key) : russian(key);
    }

    /** Pairs that can actually be granted: obsolete ones are excluded (FR-PERM-1). */
    @Transactional(readOnly = true)
    public Set<String> getGrantablePairs() {
        return permissionRepository.getGrantablePairs();
    }

    public record CatalogSyncResult(int declared, int deprecated, List<String> deprecatedPairs) {}
}
