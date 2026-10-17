package com.smartup24.cms.instance.studio.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.query.TimePage;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Declaration;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Labels;
import com.smartup24.cms.instance.studio.api.StudioFormDtos;
import com.smartup24.cms.instance.studio.api.StudioFormDtos.FormDetail;
import com.smartup24.cms.instance.studio.api.StudioFormDtos.FormSummary;
import com.smartup24.cms.instance.studio.api.StudioFormDtos.FormVersion;
import com.smartup24.cms.instance.studio.repository.StudioFormVersionRepository;
import com.smartup24.cms.instance.studio.repository.StudioFormVersionRepository.Head;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The live forms as the publisher sees them (ADR-0041): a page of forms, the newest first, and one form with the
 * declaration of its active version and its versions. Whether a form fails to load comes from the catalog snapshot of
 * the request.
 */
@Service
@Profile("!migrate")
public class StudioFormViews {
    /** The default and the largest page of forms. */
    public static final int DEFAULT_LIMIT = 50;

    public static final int MAX_LIMIT = 100;

    private static final Pattern CODE = Pattern.compile(StudioFormDtos.CODE);
    private static final Logger log = LoggerFactory.getLogger(StudioFormViews.class);

    private final StudioFormVersionRepository versions;
    private final StudioDefinitions definitions;

    public StudioFormViews(StudioFormVersionRepository versions, StudioDefinitions definitions) {
        this.versions = versions;
        this.definitions = definitions;
    }

    @Transactional(readOnly = true)
    public KeysetPage<FormSummary> list(@Nullable Integer limit, @Nullable String cursor) {
        requirePublisher();
        TimePage page = TimePage.of(limit, cursor, DEFAULT_LIMIT, MAX_LIMIT);
        StudioDefinitions.Catalog catalog = definitions.current();
        return page.page(versions.page(page), head -> new TimePage.Position(head.createdAt(), head.id()))
                .map(head -> summary(head, catalog));
    }

    @Transactional(readOnly = true)
    public FormDetail get(String entityCode) {
        requirePublisher();
        return detail(head(entityCode));
    }

    /** The form of a code from the path; a value that is no live form code is not repeated in the answer. */
    Head head(String entityCode) {
        if (!CODE.matcher(entityCode).matches()) {
            throw ApiException.notFound(ErrorCode.NOT_FOUND, "error.common.entity_not_found");
        }
        return versions.head(entityCode)
                .orElseThrow(() -> ApiException.notFound(
                        ErrorCode.NOT_FOUND, "error.studio.form_not_found", Map.of("code", entityCode)));
    }

    FormDetail detail(Head head) {
        return new FormDetail(
                summary(head, definitions.current()),
                declaration(head),
                head.checksum(),
                versions.versions(head.id()).stream()
                        .map(row -> new FormVersion(row.version(), row.checksum(), row.createdAt(), row.createdBy()))
                        .toList());
    }

    FormSummary summary(Head head) {
        return summary(head, definitions.current());
    }

    /** The declaration of the active version; a stored form that no longer reads answers 409 as broken. */
    Declaration declaration(Head head) {
        try {
            return versions.declaration(head.declaration());
        } catch (RuntimeException unreadable) {
            ApiException broken =
                    new ApiException(ErrorCode.CONFLICT, "error.studio.broken", Map.of("reason", "declaration"));
            broken.initCause(unreadable);
            throw broken;
        }
    }

    private FormSummary summary(Head head, StudioDefinitions.Catalog catalog) {
        String broken = catalog.broken().stream()
                .filter(form -> form.code().equals(head.code()))
                .map(StudioDefinitions.Broken::reason)
                .findFirst()
                .orElse(null);
        Declaration declaration = null;
        try {
            declaration = versions.declaration(head.declaration());
        } catch (RuntimeException unreadable) {
            // A stored form that does not read is listed by its code; the catalog reports why.
            log.warn("studio_form_unreadable code={} error={}", head.code(), unreadable.toString());
            broken = broken == null ? "declaration" : broken;
        }
        Labels title = declaration == null || declaration.entity() == null
                ? new Labels(head.code(), head.code(), head.code())
                : declaration.entity().title();
        return new FormSummary(
                head.code(),
                title,
                head.status(),
                head.activeVersion(),
                head.versions(),
                head.records(),
                head.quarantined(),
                broken,
                head.revision(),
                head.createdAt(),
                head.modifiedAt());
    }

    static void requirePublisher() {
        if (!SecurityContext.hasPermission("studio.forms", "publish"))
            throw ApiException.permissionDenied("studio.forms", "publish");
    }
}
