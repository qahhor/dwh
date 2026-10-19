package com.smartup24.cms.instance.studio.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.common.web.Revisions;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Declaration;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.DeliveryPackage;
import com.smartup24.cms.instance.md.service.MdPermissionService;
import com.smartup24.cms.instance.studio.api.StudioFormDtos.FormDetail;
import com.smartup24.cms.instance.studio.api.StudioFormDtos.FormSummary;
import com.smartup24.cms.instance.studio.automation.AutomationCompatibility;
import com.smartup24.cms.instance.studio.repository.StudioFormRepository;
import com.smartup24.cms.instance.studio.repository.StudioFormVersionRepository;
import com.smartup24.cms.instance.studio.repository.StudioFormVersionRepository.Head;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * New versions and the lifecycle of a live form (ADR-0041). Every change takes the revision of the form in
 * {@code If-Match} (428 without it, 409 when stale) and runs under the catalog lock, so two publishers never both
 * activate from the same revision. A new version is checked like an activation and against the active one by
 * {@link StudioChangeRules}; old versions never change. The rights, the audit and the notice to the other nodes commit
 * with the change. An archived form serves read-only and does not count toward the limit of active forms.
 */
@Service
@Profile("!migrate")
public class StudioVersionService {
    private static final String STATE_TABLE = "studio_form_state";

    private final StudioPackages packages;
    private final StudioFormRepository forms;
    private final StudioFormVersionRepository versions;
    private final StudioEntityCompiler compiler;
    private final StudioDefinitions definitions;
    private final StudioFormViews views;
    private final StudioActivationService activation;
    private final MdPermissionService permissions;
    private final AuditLogService audit;
    private final StudioTargets targets;
    private final AutomationCompatibility automations;

    public StudioVersionService(
            StudioPackages packages,
            StudioFormRepository forms,
            StudioFormVersionRepository versions,
            StudioEntityCompiler compiler,
            StudioDefinitions definitions,
            StudioFormViews views,
            StudioActivationService activation,
            MdPermissionService permissions,
            AuditLogService audit,
            StudioTargets targets,
            AutomationCompatibility automations) {
        this.packages = packages;
        this.forms = forms;
        this.versions = versions;
        this.compiler = compiler;
        this.definitions = definitions;
        this.views = views;
        this.activation = activation;
        this.permissions = permissions;
        this.audit = audit;
        this.targets = targets;
        this.automations = automations;
    }

    /** Activates a new version of {@code entityCode} from a simple-form package, from the revision named. */
    @Transactional
    public FormDetail newVersion(String entityCode, @Nullable String ifMatch, byte[] body) {
        StudioFormViews.requirePublisher();
        if (!activation.availability().enabled())
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "error.studio.disabled");
        long expected = Revisions.required(ifMatch);
        Head head = views.head(entityCode);
        DeliveryPackage pack = packages.verify(body);
        if (!entityCode.equals("studio." + pack.declaration().entity().name()))
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "error.studio.code_mismatch");
        forms.lockCatalog();
        head = current(head, expected);
        if (head.archived()) throw new ApiException(ErrorCode.CONFLICT, "error.studio.archived");
        if (head.versions() >= StudioFormVersionRepository.MAX_VERSIONS)
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "error.studio.version_limit");
        if (head.checksum().equals(pack.checksum()))
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "error.studio.version_unchanged");
        Declaration next = pack.declaration();
        List<StudioChangeRules.Change> changes = StudioChangeRules.incompatible(views.declaration(head), next);
        if (!changes.isEmpty()) throw StudioChangeRules.refusal(changes);
        var compiled = compiler.compile(head.id(), next);
        String refusal = definitions.refusal(entityCode, compiled);
        if ("declared_code".equals(refusal)) throw new ApiException(ErrorCode.CONFLICT, "error.studio.exists");
        if (refusal != null) throw new ApiException(ErrorCode.VALIDATION_FAILED, "error.studio.translation_key");
        StudioChangeRules.requireDefaults(next, compiled.entity());
        targets.require(entityCode, next);
        automations.require(entityCode, next);

        int version = head.versions() + 1;
        long userId = SecurityContext.getCurrentUserId();
        long id = versions.insertVersion(head.id(), version, next, pack.checksum(), userId);
        long revision = versions.saveState(head, version, false, userId);
        permissions.registerEntityDefinition(
                compiled.entity(), compiled.messages().get("ru"));
        audit.logChange(
                "studio_form_versions",
                Long.toString(id),
                "I",
                List.of("code", "version", "checksum"),
                Map.of(),
                Map.of("code", entityCode, "version", version, "checksum", pack.checksum()));
        auditState(head, version, false, revision);
        definitions.changed();
        return views.detail(views.head(entityCode));
    }

    /** Archives the form from the revision named: hidden from the menu, its records read-only. */
    @Transactional
    public FormSummary archive(String entityCode, @Nullable String ifMatch) {
        return lifecycle(entityCode, ifMatch, true);
    }

    /** Returns an archived form to work from the revision named, while the limit of active forms allows. */
    @Transactional
    public FormSummary restore(String entityCode, @Nullable String ifMatch) {
        return lifecycle(entityCode, ifMatch, false);
    }

    private FormSummary lifecycle(String entityCode, @Nullable String ifMatch, boolean archive) {
        StudioFormViews.requirePublisher();
        long expected = Revisions.required(ifMatch);
        Head head = views.head(entityCode);
        forms.lockCatalog();
        head = current(head, expected);
        if (archive && head.archived()) throw new ApiException(ErrorCode.CONFLICT, "error.studio.archived");
        if (!archive && !head.archived()) throw new ApiException(ErrorCode.CONFLICT, "error.studio.not_archived");
        if (!archive && forms.count() >= StudioActivationService.MAX_FORMS)
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "error.studio.quota");
        long revision = versions.saveState(head, head.activeVersion(), archive, SecurityContext.getCurrentUserId());
        auditState(head, head.activeVersion(), archive, revision);
        definitions.changed();
        return views.summary(views.head(entityCode));
    }

    /** The form as it is under the lock, when its revision is the one named; 409 otherwise. */
    private Head current(Head seen, long expected) {
        Head head = versions.head(seen.code()).orElse(seen);
        if (head.revision() != expected) throw Revisions.conflict();
        return head;
    }

    private void auditState(Head head, int version, boolean archived, long revision) {
        audit.logChange(
                STATE_TABLE,
                Long.toString(head.id()),
                "U",
                List.of("active_version", "status", "revision"),
                Map.of(
                        "code",
                        head.code(),
                        "active_version",
                        head.activeVersion(),
                        "status",
                        head.status(),
                        "revision",
                        head.revision()),
                Map.of(
                        "code",
                        head.code(),
                        "active_version",
                        version,
                        "status",
                        archived ? "archived" : "active",
                        "revision",
                        revision));
    }
}
