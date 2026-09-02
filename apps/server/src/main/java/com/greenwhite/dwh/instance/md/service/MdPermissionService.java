package com.greenwhite.dwh.instance.md.service;

import com.greenwhite.dwh.instance.md.pref.MdFormCatalog;
import com.greenwhite.dwh.instance.md.repository.MdPermissionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Service
public class MdPermissionService {

    private final MdPermissionRepository permissionRepository;

    public MdPermissionService(MdPermissionRepository permissionRepository) {
        this.permissionRepository = permissionRepository;
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

    /**
     * Приводит каталог форм в соответствие с кодом (FR-PERM-1).
     *
     * Существование пары определяется аннотациями {@code @RequiresPermission},
     * имена — справочником {@link MdFormCatalog}. Всё, чего нет среди
     * объявленных пар, помечается устаревшим, но не удаляется: удаление
     * каскадом сняло бы уже выданные права.
     *
     * @param declaredPairs пары {@code form.action}, найденные в коде
     */
    @Transactional
    public CatalogSyncResult syncFormCatalog(Set<String> declaredPairs) {
        Set<String> beforeGrantable = permissionRepository.getGrantablePairs();

        for (String pair : declaredPairs) {
            int dot = pair.lastIndexOf('.');
            String formCode = pair.substring(0, dot);
            String action = pair.substring(dot + 1);

            permissionRepository.registerForm(formCode,
                    MdFormCatalog.moduleOf(formCode), MdFormCatalog.formNameOf(formCode));
            permissionRepository.registerFormAction(formCode, action,
                    MdFormCatalog.actionNameOf(formCode, action));
        }

        int deprecated = permissionRepository.deprecateMissing(declaredPairs);

        List<String> deprecatedPairs = beforeGrantable.stream()
                .filter(pair -> !declaredPairs.contains(pair))
                .sorted()
                .toList();

        return new CatalogSyncResult(declaredPairs.size(), deprecated, deprecatedPairs);
    }

    /** Пары, которые реально можно выдать: устаревшие исключены (FR-PERM-1). */
    @Transactional(readOnly = true)
    public Set<String> getGrantablePairs() {
        return permissionRepository.getGrantablePairs();
    }

    public record CatalogSyncResult(int declared, int deprecated, List<String> deprecatedPairs) {}
}
