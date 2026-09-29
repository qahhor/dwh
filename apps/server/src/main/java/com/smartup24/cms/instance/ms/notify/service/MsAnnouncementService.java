package com.smartup24.cms.instance.ms.notify.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.ms.notify.api.AnnouncementDraftRequest;
import com.smartup24.cms.instance.ms.notify.api.ManagedAnnouncementView;
import com.smartup24.cms.instance.ms.notify.model.AnnouncementState;
import com.smartup24.cms.instance.ms.notify.repository.MsAnnouncementRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MsAnnouncementService {

    private static final int MAX_LOCALIZED_VALUE_LENGTH = 10_000;
    private static final int MAX_LOCALES = 20;
    private static final Set<String> BANNER_TYPES = Set.of("INFO", "WARNING", "CRITICAL");

    /** A localized field of the draft and the keys of its errors: each language names the field in its own words. */
    private enum LocalizedField {
        TITLE(
                "error.notify.announcement_title_required",
                "error.notify.announcement_title_too_many_languages",
                "error.notify.announcement_title_entry_required",
                "error.notify.announcement_title_too_long",
                "error.notify.announcement_title_ru_required"),
        BODY(
                "error.notify.announcement_body_required",
                "error.notify.announcement_body_too_many_languages",
                "error.notify.announcement_body_entry_required",
                "error.notify.announcement_body_too_long",
                "error.notify.announcement_body_ru_required");

        private final String required;
        private final String tooManyLanguages;
        private final String entryRequired;
        private final String tooLong;
        private final String russianRequired;

        LocalizedField(
                String required,
                String tooManyLanguages,
                String entryRequired,
                String tooLong,
                String russianRequired) {
            this.required = required;
            this.tooManyLanguages = tooManyLanguages;
            this.entryRequired = entryRequired;
            this.tooLong = tooLong;
            this.russianRequired = russianRequired;
        }
    }

    private final MsAnnouncementRepository repository;
    private final AuditLogService auditLogService;

    public MsAnnouncementService(MsAnnouncementRepository repository, AuditLogService auditLogService) {
        this.repository = repository;
        this.auditLogService = auditLogService;
    }

    @Transactional(readOnly = true)
    public List<ManagedAnnouncementView> listAll() {
        return repository.findAll().stream().map(MsNotifyViews::managed).toList();
    }

    @Transactional
    public ManagedAnnouncementView create(AnnouncementDraftRequest request, Long authorId) {
        validateDraft(request, false);
        if (authorId == null) {
            throw ApiException.unauthorized("error.notify.not_authenticated");
        }

        var created = repository.create(
                request.titleJson(), request.bodyJson(), normalizedBannerType(request.bannerType()), authorId);
        auditLogService.logChange(
                "ms_announcements",
                String.valueOf(created.id()),
                "I",
                List.of("title_json", "body_json", "banner_type", "state", "created_by"),
                Map.of(),
                snapshot(created));
        return MsNotifyViews.managed(created);
    }

    @Transactional
    public ManagedAnnouncementView update(Long id, AnnouncementDraftRequest request) {
        validateDraft(request, true);
        var current = getById(id);
        requireState(current, AnnouncementState.DRAFT, "error.notify.announcement_edit_draft_only");
        requireCurrentVersion(current, request.lockVersion());

        var updated = repository
                .updateDraft(
                        id,
                        request.titleJson(),
                        request.bodyJson(),
                        normalizedBannerType(request.bannerType()),
                        request.lockVersion())
                .orElseThrow(MsAnnouncementService::staleVersion);
        auditMutation(
                current, updated, List.of("title_json", "body_json", "banner_type", "modified_at", "lock_version"));
        return MsNotifyViews.managed(updated);
    }

    @Transactional
    public ManagedAnnouncementView publish(Long id, Long lockVersion) {
        requireValidVersion(lockVersion);
        var current = getById(id);
        requireState(current, AnnouncementState.DRAFT, "error.notify.announcement_publish_draft_only");
        requireCurrentVersion(current, lockVersion);

        var published = repository.publish(id, lockVersion).orElseThrow(MsAnnouncementService::staleVersion);
        auditMutation(current, published, List.of("state", "published_at", "modified_at", "lock_version"));
        return MsNotifyViews.managed(published);
    }

    @Transactional
    public ManagedAnnouncementView archive(Long id, Long lockVersion) {
        requireValidVersion(lockVersion);
        var current = getById(id);
        requireState(current, AnnouncementState.PUBLISHED, "error.notify.announcement_archive_published_only");
        requireCurrentVersion(current, lockVersion);

        var archived = repository.archive(id, lockVersion).orElseThrow(MsAnnouncementService::staleVersion);
        auditMutation(current, archived, List.of("state", "archived_at", "modified_at", "lock_version"));
        return MsNotifyViews.managed(archived);
    }

    private MsAnnouncementRepository.ManagedAnnouncementRecord getById(Long id) {
        if (id == null) {
            throw ApiException.badRequest(ErrorCode.BAD_REQUEST, "error.notify.announcement_id_required");
        }
        return repository
                .findById(id)
                .orElseThrow(() -> ApiException.notFound(
                        ErrorCode.NOT_FOUND, "error.notify.announcement_not_found", Map.of("id", id)));
    }

    private void auditMutation(
            MsAnnouncementRepository.ManagedAnnouncementRecord oldValue,
            MsAnnouncementRepository.ManagedAnnouncementRecord newValue,
            List<String> columns) {
        auditLogService.logChange(
                "ms_announcements",
                String.valueOf(newValue.id()),
                "U",
                columns,
                snapshot(oldValue),
                snapshot(newValue));
    }

    private static void validateDraft(AnnouncementDraftRequest request, boolean requireVersion) {
        if (request == null) {
            throw ApiException.badRequest(ErrorCode.BAD_REQUEST, "error.notify.announcement_request_required");
        }
        validateLocalizedValues(LocalizedField.TITLE, request.titleJson());
        validateLocalizedValues(LocalizedField.BODY, request.bodyJson());
        requireRussianValue(LocalizedField.TITLE, request.titleJson());
        requireRussianValue(LocalizedField.BODY, request.bodyJson());
        normalizedBannerType(request.bannerType());
        if (requireVersion) {
            requireValidVersion(request.lockVersion());
        }
    }

    private static void validateLocalizedValues(LocalizedField field, Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            throw ApiException.badRequest(ErrorCode.BAD_REQUEST, field.required);
        }
        if (values.size() > MAX_LOCALES) {
            throw ApiException.badRequest(ErrorCode.BAD_REQUEST, field.tooManyLanguages, Map.of("max", MAX_LOCALES));
        }
        values.forEach((language, value) -> {
            if (language == null || language.isBlank() || value == null) {
                throw ApiException.badRequest(ErrorCode.BAD_REQUEST, field.entryRequired);
            }
            if (value.length() > MAX_LOCALIZED_VALUE_LENGTH) {
                throw ApiException.badRequest(
                        ErrorCode.BAD_REQUEST, field.tooLong, Map.of("max", MAX_LOCALIZED_VALUE_LENGTH));
            }
        });
    }

    private static void requireRussianValue(LocalizedField field, Map<String, String> values) {
        String russian = values.get("ru");
        if (russian == null || russian.isBlank()) {
            throw ApiException.badRequest(ErrorCode.BAD_REQUEST, field.russianRequired);
        }
    }

    private static String normalizedBannerType(String bannerType) {
        String normalized = bannerType == null ? "" : bannerType.trim().toUpperCase(Locale.ROOT);
        if (!BANNER_TYPES.contains(normalized)) {
            throw ApiException.badRequest(ErrorCode.BAD_REQUEST, "error.notify.banner_type_invalid");
        }
        return normalized;
    }

    private static void requireState(
            MsAnnouncementRepository.ManagedAnnouncementRecord current, AnnouncementState expected, String messageKey) {
        if (current.state() != expected) {
            throw ApiException.conflict(ErrorCode.STATUS_TRANSITION_FORBIDDEN, messageKey);
        }
    }

    private static void requireValidVersion(Long lockVersion) {
        if (lockVersion == null || lockVersion < 0) {
            throw ApiException.badRequest(ErrorCode.BAD_REQUEST, "error.notify.lock_version_invalid");
        }
    }

    private static void requireCurrentVersion(
            MsAnnouncementRepository.ManagedAnnouncementRecord current, Long lockVersion) {
        if (current.lockVersion() != lockVersion) {
            throw staleVersion();
        }
    }

    private static ApiException staleVersion() {
        return ApiException.conflict(ErrorCode.CONFLICT, "error.notify.announcement_stale");
    }

    private static Map<String, Object> snapshot(MsAnnouncementRepository.ManagedAnnouncementRecord announcement) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", announcement.titleJson());
        result.put("body", announcement.bodyJson());
        result.put("bannerType", announcement.bannerType());
        result.put("state", announcement.state().name());
        result.put("lockVersion", announcement.lockVersion());
        if (announcement.createdBy() != null) {
            result.put("createdBy", announcement.createdBy());
        }
        if (announcement.publishedAt() != null) {
            result.put("publishedAt", announcement.publishedAt().toString());
        }
        if (announcement.archivedAt() != null) {
            result.put("archivedAt", announcement.archivedAt().toString());
        }
        return Map.copyOf(result);
    }
}
