package com.smartup24.cms.instance.studio.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.entity.EntityRegistry;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.module.InstalledModules;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos;
import com.smartup24.cms.instance.md.service.MdPermissionService;
import com.smartup24.cms.instance.studio.api.StudioFormDtos.Activated;
import com.smartup24.cms.instance.studio.api.StudioFormDtos.Availability;
import com.smartup24.cms.instance.studio.repository.StudioFormRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Declaration, permissions and audit commit together; no runtime DDL (ADR-0038). At most {@link #MAX_FORMS} forms are
 * active; archived forms do not count toward it, every stored form counts toward
 * {@link StudioFormRepository#MAX_STORED} (ADR-0041).
 */
@Service
public class StudioActivationService {
    public static final int MAX_FORMS = 100;
    private final StudioPackages packages;
    private final StudioFormRepository repository;
    private final StudioEntityCompiler compiler;
    private final MdPermissionService permissions;
    private final AuditLogService audit;
    private final InstalledModules modules;
    private final boolean enabled;
    private final @Nullable EntityRegistry entities;
    private final @Nullable StudioDefinitions definitions;

    /** The activated form and whether this request created it; an exact repeat creates nothing (ADR-0038). */
    public record Outcome(Activated form, boolean created) {}

    @Autowired
    public StudioActivationService(
            StudioPackages packages,
            StudioFormRepository repository,
            StudioEntityCompiler compiler,
            MdPermissionService permissions,
            AuditLogService audit,
            InstalledModules modules,
            @Value("${smc.studio.activation-enabled:false}") boolean enabled,
            @Nullable EntityRegistry entities,
            ObjectProvider<StudioDefinitions> definitions) {
        this(
                packages,
                repository,
                compiler,
                permissions,
                audit,
                modules,
                enabled,
                entities,
                definitions.getIfAvailable());
    }

    public StudioActivationService(
            StudioPackages packages,
            StudioFormRepository repository,
            StudioEntityCompiler compiler,
            MdPermissionService permissions,
            AuditLogService audit,
            InstalledModules modules,
            boolean enabled,
            @Nullable EntityRegistry entities,
            @Nullable StudioDefinitions definitions) {
        this.packages = packages;
        this.repository = repository;
        this.compiler = compiler;
        this.permissions = permissions;
        this.audit = audit;
        this.modules = modules;
        this.enabled = enabled;
        this.entities = entities;
        this.definitions = definitions;
    }

    public StudioActivationService(
            StudioPackages packages,
            StudioFormRepository repository,
            StudioEntityCompiler compiler,
            MdPermissionService permissions,
            AuditLogService audit,
            InstalledModules modules,
            boolean enabled) {
        this(packages, repository, compiler, permissions, audit, modules, enabled, null);
    }

    public StudioActivationService(
            StudioPackages packages,
            StudioFormRepository repository,
            StudioEntityCompiler compiler,
            MdPermissionService permissions,
            AuditLogService audit,
            InstalledModules modules,
            boolean enabled,
            @Nullable EntityRegistry entities) {
        this(packages, repository, compiler, permissions, audit, modules, enabled, entities, (StudioDefinitions) null);
    }

    public Availability availability() {
        return new Availability(enabled && modules.active("studio"), "studio", MAX_FORMS);
    }

    @Transactional
    public Outcome activate(byte[] body) {
        if (!SecurityContext.hasPermission("studio.forms", "publish"))
            throw ApiException.permissionDenied("studio.forms", "publish");
        if (!availability().enabled()) throw new ApiException(ErrorCode.VALIDATION_FAILED, "error.studio.disabled");
        var pack = packages.verify(body);
        String code = "studio." + pack.declaration().entity().name();
        repository.lockCatalog();
        var existing = repository.checksum(code);
        if (existing.isPresent()) {
            if (!existing.get().equals(pack.checksum()))
                throw new ApiException(ErrorCode.CONFLICT, "error.studio.exists");
            requireServed(code);
            return new Outcome(new Activated(code, pack.checksum()), false);
        }
        if (entities != null && entities.find(code).isPresent()) {
            throw new ApiException(ErrorCode.CONFLICT, "error.studio.exists");
        }
        refuseWhatWouldNotLoad(code, pack.declaration());
        StudioChangeRules.requireDefaults(
                pack.declaration(),
                compiler.compile(Long.MAX_VALUE, pack.declaration()).entity());
        // Archived forms do not count toward the active limit; every stored form counts toward the stored one.
        if (repository.count() >= MAX_FORMS) throw new ApiException(ErrorCode.VALIDATION_FAILED, "error.studio.quota");
        if (repository.stored() >= StudioFormRepository.MAX_STORED)
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "error.studio.stored_quota");
        long id = repository.insert(code, pack.declaration(), pack.checksum(), SecurityContext.getCurrentUserId());
        var compiled = compiler.compile(id, pack.declaration());
        permissions.registerEntityDefinition(
                compiled.entity(), compiled.messages().get("ru"));
        audit.logChange(
                "studio_forms",
                Long.toString(id),
                "I",
                List.of("code", "checksum"),
                Map.of(),
                Map.of("code", code, "checksum", pack.checksum()));
        if (definitions != null) definitions.changed();
        return new Outcome(new Activated(code, pack.checksum()), true);
    }

    /**
     * An exact repeat answers "activated" only while the form is served: a quarantined form answers 409
     * {@code error.studio.quarantined}, a stored form that does not load 409 {@code error.studio.broken} with its reason
     * (ADR-0038, decision 12).
     */
    private void requireServed(String code) {
        if (definitions == null) return;
        Optional<Long> id = repository.id(code);
        if (id.isPresent() && repository.held(id.get()).isPresent())
            throw new ApiException(ErrorCode.CONFLICT, "error.studio.quarantined");
        StudioDefinitions.Catalog catalog = definitions.current();
        if (catalog.served().containsKey(code)) return;
        String reason = catalog.broken().stream()
                .filter(broken -> broken.code().equals(code))
                .map(StudioDefinitions.Broken::reason)
                .findFirst()
                .orElse("declaration");
        throw new ApiException(ErrorCode.CONFLICT, "error.studio.broken", Map.of("reason", reason));
    }

    /**
     * The checks that leave a stored form out (ADR-0038, decision 9), run before the form is stored: a declared code
     * answers 409 like an occupied one, labels that shadow bundled translation keys 422.
     */
    private void refuseWhatWouldNotLoad(String code, MdSimpleFormDtos.Declaration declaration) {
        if (definitions == null) return;
        // The partition key is not known before the insert; the checks read the code and the labels only.
        String refusal = definitions.refusal(code, compiler.compile(Long.MAX_VALUE, declaration));
        if (refusal == null) return;
        if ("declared_code".equals(refusal)) throw new ApiException(ErrorCode.CONFLICT, "error.studio.exists");
        throw new ApiException(ErrorCode.VALIDATION_FAILED, "error.studio.translation_key");
    }
}
