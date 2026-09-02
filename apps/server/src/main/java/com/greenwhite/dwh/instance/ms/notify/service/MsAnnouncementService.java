package com.greenwhite.dwh.instance.ms.notify.service;

import com.greenwhite.dwh.core.error.ErrorCode;
import com.greenwhite.dwh.instance.audit.service.AuditLogService;
import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.ms.notify.model.AnnouncementDraftRequest;
import com.greenwhite.dwh.instance.ms.notify.model.AnnouncementState;
import com.greenwhite.dwh.instance.ms.notify.repository.MsAnnouncementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class MsAnnouncementService {

    private static final int MAX_LOCALIZED_VALUE_LENGTH = 10_000;
    private static final int MAX_LOCALES = 20;
    private static final Set<String> BANNER_TYPES = Set.of("INFO", "WARNING", "CRITICAL");

    private final MsAnnouncementRepository repository;
    private final AuditLogService auditLogService;

    public MsAnnouncementService(MsAnnouncementRepository repository, AuditLogService auditLogService) {
        this.repository = repository;
        this.auditLogService = auditLogService;
    }

    @Transactional(readOnly = true)
    public List<MsAnnouncementRepository.ManagedAnnouncementRecord> listAll() {
        return repository.findAll();
    }

    @Transactional
    public MsAnnouncementRepository.ManagedAnnouncementRecord create(
            AnnouncementDraftRequest request, Long authorId) {
        validateDraft(request, false);
        if (authorId == null) {
            throw ApiException.unauthorized("Пользователь не авторизован");
        }

        var created = repository.create(
                request.titleJson(),
                request.bodyJson(),
                normalizedBannerType(request.bannerType()),
                authorId);
        auditLogService.logChange(
                "ms_announcements",
                String.valueOf(created.id()),
                "I",
                List.of("title_json", "body_json", "banner_type", "state", "created_by"),
                Map.of(),
                snapshot(created));
        return created;
    }

    @Transactional
    public MsAnnouncementRepository.ManagedAnnouncementRecord update(
            Long id, AnnouncementDraftRequest request) {
        validateDraft(request, true);
        var current = getById(id);
        requireState(current, AnnouncementState.DRAFT, "Редактировать можно только черновик");
        requireCurrentVersion(current, request.lockVersion());

        var updated = repository.updateDraft(
                        id,
                        request.titleJson(),
                        request.bodyJson(),
                        normalizedBannerType(request.bannerType()),
                        request.lockVersion())
                .orElseThrow(MsAnnouncementService::staleVersion);
        auditMutation(current, updated, List.of(
                "title_json", "body_json", "banner_type", "modified_at", "lock_version"));
        return updated;
    }

    @Transactional
    public MsAnnouncementRepository.ManagedAnnouncementRecord publish(Long id, Long lockVersion) {
        requireValidVersion(lockVersion);
        var current = getById(id);
        requireState(current, AnnouncementState.DRAFT,
                "Опубликовать можно только объявление в статусе DRAFT");
        requireCurrentVersion(current, lockVersion);

        var published = repository.publish(id, lockVersion)
                .orElseThrow(MsAnnouncementService::staleVersion);
        auditMutation(current, published, List.of(
                "state", "published_at", "modified_at", "lock_version"));
        return published;
    }

    @Transactional
    public MsAnnouncementRepository.ManagedAnnouncementRecord archive(Long id, Long lockVersion) {
        requireValidVersion(lockVersion);
        var current = getById(id);
        requireState(current, AnnouncementState.PUBLISHED,
                "Архивировать можно только опубликованное объявление");
        requireCurrentVersion(current, lockVersion);

        var archived = repository.archive(id, lockVersion)
                .orElseThrow(MsAnnouncementService::staleVersion);
        auditMutation(current, archived, List.of(
                "state", "archived_at", "modified_at", "lock_version"));
        return archived;
    }

    private MsAnnouncementRepository.ManagedAnnouncementRecord getById(Long id) {
        if (id == null) {
            throw ApiException.badRequest(ErrorCode.BAD_REQUEST, "Идентификатор объявления обязателен");
        }
        return repository.findById(id)
                .orElseThrow(() -> ApiException.notFound(
                        ErrorCode.NOT_FOUND, "Объявление не найдено: " + id));
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
            throw ApiException.badRequest(ErrorCode.BAD_REQUEST, "Тело объявления обязательно");
        }
        validateLocalizedValues("заголовок", request.titleJson());
        validateLocalizedValues("текст", request.bodyJson());
        requireRussianValue("заголовок", request.titleJson());
        requireRussianValue("текст", request.bodyJson());
        normalizedBannerType(request.bannerType());
        if (requireVersion) {
            requireValidVersion(request.lockVersion());
        }
    }

    private static void validateLocalizedValues(String field, Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            throw ApiException.badRequest(ErrorCode.BAD_REQUEST,
                    "Локализованный " + field + " обязателен");
        }
        if (values.size() > MAX_LOCALES) {
            throw ApiException.badRequest(ErrorCode.BAD_REQUEST,
                    "Для поля " + field + " допускается не более " + MAX_LOCALES + " языков");
        }
        values.forEach((language, value) -> {
            if (language == null || language.isBlank() || value == null) {
                throw ApiException.badRequest(ErrorCode.BAD_REQUEST,
                        "Код языка и значение поля " + field + " обязательны");
            }
            if (value.length() > MAX_LOCALIZED_VALUE_LENGTH) {
                throw ApiException.badRequest(ErrorCode.BAD_REQUEST,
                        "Значение поля " + field + " не должно превышать 10000 символов");
            }
        });
    }

    private static void requireRussianValue(String field, Map<String, String> values) {
        String russian = values.get("ru");
        if (russian == null || russian.isBlank()) {
            throw ApiException.badRequest(ErrorCode.BAD_REQUEST,
                    "RU " + field + " обязателен");
        }
    }

    private static String normalizedBannerType(String bannerType) {
        String normalized = bannerType == null ? "" : bannerType.trim().toUpperCase(Locale.ROOT);
        if (!BANNER_TYPES.contains(normalized)) {
            throw ApiException.badRequest(ErrorCode.BAD_REQUEST,
                    "Тип баннера должен быть INFO, WARNING или CRITICAL");
        }
        return normalized;
    }

    private static void requireState(
            MsAnnouncementRepository.ManagedAnnouncementRecord current,
            AnnouncementState expected,
            String message) {
        if (current.state() != expected) {
            throw ApiException.conflict(ErrorCode.STATUS_TRANSITION_FORBIDDEN, message);
        }
    }

    private static void requireValidVersion(Long lockVersion) {
        if (lockVersion == null || lockVersion < 0) {
            throw ApiException.badRequest(ErrorCode.BAD_REQUEST,
                    "lockVersion должен быть неотрицательным числом");
        }
    }

    private static void requireCurrentVersion(
            MsAnnouncementRepository.ManagedAnnouncementRecord current, Long lockVersion) {
        if (current.lockVersion() != lockVersion) {
            throw staleVersion();
        }
    }

    private static ApiException staleVersion() {
        return ApiException.conflict(ErrorCode.CONFLICT,
                "Объявление уже изменено другим запросом; обновите данные и повторите действие");
    }

    private static Map<String, Object> snapshot(
            MsAnnouncementRepository.ManagedAnnouncementRecord announcement) {
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
