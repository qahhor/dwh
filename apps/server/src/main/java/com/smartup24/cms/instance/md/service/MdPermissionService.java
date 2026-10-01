package com.smartup24.cms.instance.md.service;

import com.smartup24.cms.instance.common.entity.EntityDefinition.EntityRights;
import com.smartup24.cms.instance.common.entity.EntityRegistry;
import com.smartup24.cms.instance.md.api.MdRoleDtos.FormCatalogItem;
import com.smartup24.cms.instance.md.pref.MdFormCatalog;
import com.smartup24.cms.instance.md.repository.MdPermissionRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MdPermissionService {

    private final MdPermissionRepository permissionRepository;
    /** Declared entities name their own rights (roadmap item 57); null — only the catalog names them. */
    private final EntityRegistry entities;

    @Autowired
    public MdPermissionService(MdPermissionRepository permissionRepository, @Lazy EntityRegistry entities) {
        this.permissionRepository = permissionRepository;
        this.entities = entities;
    }

    public MdPermissionService(MdPermissionRepository permissionRepository) {
        this(permissionRepository, null);
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
                .map(i -> new FormCatalogItem(
                        i.formCode(), i.module(), i.formName(), i.action(), i.actionName(), i.isDeprecated()))
                .toList();
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
        Set<String> beforeGrantable = permissionRepository.getGrantablePairs();

        for (String pair : declaredPairs) {
            int dot = pair.lastIndexOf('.');
            String formCode = pair.substring(0, dot);
            String action = pair.substring(dot + 1);

            Optional<EntityRights> rights = entities == null ? Optional.empty() : entities.rights(formCode);
            permissionRepository.registerForm(
                    formCode,
                    rights.map(EntityRights::module).orElseGet(() -> MdFormCatalog.moduleOf(formCode)),
                    rights.map(EntityRights::name).orElseGet(() -> MdFormCatalog.formNameOf(formCode)));
            permissionRepository.registerFormAction(
                    formCode,
                    action,
                    rights.map(named -> named.actionNames().get(action))
                            .orElseGet(() -> MdFormCatalog.actionNameOf(formCode, action)));
        }

        int deprecated = permissionRepository.deprecateMissing(declaredPairs);

        List<String> deprecatedPairs = beforeGrantable.stream()
                .filter(pair -> !declaredPairs.contains(pair))
                .sorted()
                .toList();

        return new CatalogSyncResult(declaredPairs.size(), deprecated, deprecatedPairs);
    }

    /** Pairs that can actually be granted: obsolete ones are excluded (FR-PERM-1). */
    @Transactional(readOnly = true)
    public Set<String> getGrantablePairs() {
        return permissionRepository.getGrantablePairs();
    }

    public record CatalogSyncResult(int declared, int deprecated, List<String> deprecatedPairs) {}
}
