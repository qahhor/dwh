package com.smartup24.cms.instance.md.service;

import com.smartup24.cms.instance.md.api.MdInstanceOrganization;
import com.smartup24.cms.instance.md.repository.MdInstanceRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The md side of an instance's first start (FR-INST-1): the wiring decides what the deployment configuration
 * requires, md owns the tables it writes (ADR-0026). Every step is idempotent.
 */
@Service
public class MdInstanceService {

    private final MdInstanceRepository repository;
    private final PasswordHasher passwordHasher;
    private final MdPermissionService permissionService;

    public MdInstanceService(
            MdInstanceRepository repository, PasswordHasher passwordHasher, MdPermissionService permissionService) {
        this.repository = repository;
        this.passwordHasher = passwordHasher;
        this.permissionService = permissionService;
    }

    /** Whether the instance record exists. */
    public boolean instanceRecorded() {
        return repository.instanceRecorded();
    }

    /** Records the organization the instance serves. */
    @Transactional
    public void recordInstance(String code, String name, String resourceProfile) {
        repository.recordInstance(code, name, resourceProfile);
    }

    /** The organization recorded at the first start, if it is recorded. */
    public Optional<MdInstanceOrganization> organization() {
        return repository.organization();
    }

    /** Whether any user exists. */
    public boolean anyUser() {
        return repository.anyUser();
    }

    /**
     * Creates the first administrator with the administrator role and its effective permissions; the first sign-in
     * requires a new password. The password is hashed here and never kept.
     */
    @Transactional
    public long createFirstAdmin(String login, String email, String password) {
        long userId = repository.insertFirstAdmin(login, email, passwordHasher.hashPassword(password));
        repository.grantAdminRole(userId);
        permissionService.recalculateEffectivePermissions(userId);
        return userId;
    }

    /** Adds the effective permissions the active roles grant and that are not yet materialized. */
    @Transactional
    public void addMissingEffectivePermissions() {
        repository.addMissingEffectivePermissions();
    }
}
