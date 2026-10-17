package com.smartup24.cms.instance.studio.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.studio.api.StudioFormDtos;
import com.smartup24.cms.instance.studio.api.StudioFormDtos.BrokenForm;
import com.smartup24.cms.instance.studio.api.StudioFormDtos.CatalogStatus;
import com.smartup24.cms.instance.studio.api.StudioFormDtos.QuarantinedForm;
import com.smartup24.cms.instance.studio.repository.StudioFormRepository;
import com.smartup24.cms.instance.studio.repository.StudioFormRepository.Held;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Withdraws a stored live form from serving and returns it, without touching its immutable declaration or records
 * (ADR-0038). A quarantined form answers as an unknown entity; its rights stay in the catalog. Each change is audited
 * and seen by the next request of every node through the catalog version.
 */
@Service
@Profile("!migrate")
public class StudioQuarantineService {
    private static final String AUDIT_TABLE = "studio_form_quarantine";
    private static final Pattern CODE = Pattern.compile(StudioFormDtos.CODE);

    private final StudioFormRepository repository;
    private final StudioDefinitions definitions;
    private final AuditLogService audit;

    public StudioQuarantineService(
            StudioFormRepository repository, StudioDefinitions definitions, AuditLogService audit) {
        this.repository = repository;
        this.definitions = definitions;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public CatalogStatus status() {
        requirePublisher();
        StudioDefinitions.Catalog catalog = definitions.current();
        return new CatalogStatus(
                (int) catalog.version().forms(),
                catalog.served().size(),
                catalog.broken().stream()
                        .map(broken -> new BrokenForm(broken.code(), broken.reason()))
                        .toList(),
                repository.held().stream().map(StudioQuarantineService::view).toList());
    }

    @Transactional(readOnly = true)
    public QuarantinedForm get(String entityCode) {
        requirePublisher();
        return view(repository.held(heldFormId(entityCode)).orElseThrow(StudioQuarantineService::notHeld));
    }

    @Transactional
    public QuarantinedForm quarantine(String entityCode, String reason) {
        requirePublisher();
        long formId = formId(entityCode);
        repository.lockCatalog();
        String text = reason.strip();
        Held held = repository
                .quarantine(formId, text, SecurityContext.getCurrentUserId())
                .orElseThrow(() -> new ApiException(ErrorCode.CONFLICT, "error.studio.quarantined"));
        audit.logChange(
                AUDIT_TABLE,
                Long.toString(formId),
                "I",
                List.of("code", "reason"),
                Map.of(),
                Map.of("code", held.code(), "reason", text));
        definitions.changed();
        return view(held);
    }

    @Transactional
    public void release(String entityCode) {
        requirePublisher();
        long formId = heldFormId(entityCode);
        repository.lockCatalog();
        Held held = repository.held(formId).orElseThrow(StudioQuarantineService::notHeld);
        repository.release(formId);
        audit.logChange(
                AUDIT_TABLE,
                Long.toString(formId),
                "D",
                List.of("code", "reason"),
                Map.of("code", held.code(), "reason", held.reason()),
                Map.of());
        definitions.changed();
    }

    /**
     * The form of a code taken from the path: a value that is not a live form code is answered as a form that is not
     * quarantined, without repeating it.
     */
    private long heldFormId(String entityCode) {
        if (!CODE.matcher(entityCode).matches()) throw notHeld();
        return formId(entityCode);
    }

    private long formId(String entityCode) {
        return repository
                .id(entityCode)
                .orElseThrow(() -> ApiException.notFound(
                        ErrorCode.NOT_FOUND, "error.studio.form_not_found", Map.of("code", entityCode)));
    }

    private static void requirePublisher() {
        if (!SecurityContext.hasPermission("studio.forms", "publish"))
            throw ApiException.permissionDenied("studio.forms", "publish");
    }

    private static ApiException notHeld() {
        return ApiException.notFound(ErrorCode.NOT_FOUND, "error.studio.not_quarantined");
    }

    private static QuarantinedForm view(Held held) {
        return new QuarantinedForm(held.code(), held.reason(), held.quarantinedAt(), held.quarantinedBy());
    }
}
