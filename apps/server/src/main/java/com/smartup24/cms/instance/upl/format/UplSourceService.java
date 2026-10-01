package com.smartup24.cms.instance.upl.format;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.common.actor.AuditActor;
import com.smartup24.cms.instance.common.actor.AuditActorContext;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.error.ConstraintViolationException;
import com.smartup24.cms.instance.common.query.QueryCompiler;
import com.smartup24.cms.instance.common.versioning.Version;
import com.smartup24.cms.instance.common.versioning.VersionErrors;
import com.smartup24.cms.instance.common.versioning.Versions;
import com.smartup24.cms.instance.upl.UplPref;
import com.smartup24.cms.instance.upl.format.UplFormatModel.FileKind;
import com.smartup24.cms.instance.upl.format.UplFormatModel.FormatVersion;
import com.smartup24.cms.instance.upl.format.UplFormatModel.MatchBy;
import com.smartup24.cms.instance.upl.format.UplFormatModel.Sheet;
import com.smartup24.cms.instance.upl.format.UplFormatModel.Source;
import com.smartup24.cms.instance.upl.format.UplFormatModel.SourceData;
import com.smartup24.cms.instance.upl.format.UplFormatModel.SourceSummary;
import com.smartup24.cms.instance.upl.format.UplFormatModel.SourceType;
import com.smartup24.cms.instance.upl.format.UplFormatModel.Strictness;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** File format: source, draft, publication with checks, reading versions. */
@Service
public class UplSourceService {

    /** Codes of field errors ({@code errors[].code}): the web maps them to its own texts. */
    public static final String UPL_SOURCE_CODE_IMMUTABLE = "UPL_SOURCE_CODE_IMMUTABLE";

    public static final String VALID_FROM_REQUIRED = "VALID_FROM_REQUIRED";

    private static final String TABLE = UplPref.TABLE_FORMAT_VERSIONS;
    private static final String CODE_UNIQUE_INDEX = "upl_sources_code_uidx";

    private final UplFormatRepository repo;
    private final UplFormatValidator validator;
    private final Versions versioning;
    private final AuditActorContext actors;

    public UplSourceService(
            UplFormatRepository repo, UplFormatValidator validator, Versions versioning, AuditActorContext actors) {
        this.repo = repo;
        this.validator = validator;
        this.versioning = versioning;
        this.actors = actors;
    }

    public record SourceView(Source source, Integer lastPublishedVersion, boolean hasDraft) {}

    public record DraftData(
            FileKind fileKind, String encoding, String delimiter, MatchBy matchColumnsBy, List<Sheet> sheets) {}

    @Transactional
    public SourceView createSource(SourceData d, long userId) {
        AuditActor actor = actors.user(userId);
        actors.apply(actor);
        SourceData data = new SourceData(
                d.code(),
                d.name(),
                d.ownerOrg(),
                d.ownerContact(),
                d.periodicity(),
                d.slaDays(),
                d.sourceType() == null ? SourceType.FILE : d.sourceType(),
                d.strictness() == null ? Strictness.ERROR : d.strictness());
        long id;
        try {
            id = repo.insertSource(data, actor.name());
        } catch (DataIntegrityViolationException e) {
            if (UplErrors.chainContains(e, CODE_UNIQUE_INDEX)) {
                throw ApiException.badRequest(ErrorCode.CODE_ALREADY_EXISTS, "error.upl.source_code_taken");
            }
            throw UplErrors.toApi(e);
        }
        return view(id);
    }

    @Transactional
    public SourceView updateSource(long id, int lockVersion, SourceData d, long userId) {
        AuditActor actor = actors.user(userId);
        actors.apply(actor);
        Source current = requireSource(id);
        if (d.code() != null && !d.code().equals(current.code())) {
            throw ApiException.validation(
                    "error.upl.source_code_immutable",
                    List.of(new FieldErrorItem("code", UPL_SOURCE_CODE_IMMUTABLE, UPL_SOURCE_CODE_IMMUTABLE)));
        }
        SourceData data = new SourceData(
                current.code(),
                d.name(),
                d.ownerOrg(),
                d.ownerContact(),
                d.periodicity(),
                d.slaDays(),
                d.sourceType() == null ? current.sourceType() : d.sourceType(),
                d.strictness() == null ? current.strictness() : d.strictness());
        int updated;
        try {
            updated = repo.updateSource(id, lockVersion, data, actor.name());
        } catch (DataAccessException e) {
            throw UplErrors.toApi(e);
        }
        if (updated == 0) {
            throw ApiException.conflict(ErrorCode.CONFLICT, "error.upl.stale_version");
        }
        return view(id);
    }

    @Transactional(readOnly = true)
    public SourceView getSource(long id) {
        return view(id);
    }

    @Transactional(readOnly = true)
    public KeysetPage<SourceSummary> listSources(int limit, String cursor) {
        return listSources(limit, cursor, null, null, null);
    }

    /**
     * Registry list: the filter is the {@link QueryCompiler} DSL, the sort is a field key with a minus for
     * descending.
     */
    @Transactional(readOnly = true)
    public KeysetPage<SourceSummary> listSources(
            Integer limit, String cursor, String filter, String sort, String search) {
        return repo.pageSources(QueryCompiler.compile(UplSourceQuery.LIST, filter, sort, limit, cursor, search));
    }

    @Transactional(readOnly = true)
    public List<FormatVersion> listVersions(long sourceId) {
        requireSource(sourceId);
        return repo.listVersions(sourceId);
    }

    @Transactional(readOnly = true)
    public FormatVersion getVersion(long sourceId, int version) {
        requireSource(sourceId);
        return repo.findVersion(sourceId, version)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.NOT_FOUND, "error.upl.fnd_version_unknown"));
    }

    @Transactional(readOnly = true)
    public FormatVersion versionAt(long sourceId, LocalDate at) {
        requireSource(sourceId);
        int version = versioning
                .versionAt(TABLE, sourceId, at)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.NOT_FOUND, "error.upl.fnd_version_unknown"));
        return getVersion(sourceId, version);
    }

    @Transactional
    public FormatVersion createDraft(long sourceId, Integer copyFrom, long userId) {
        AuditActor actor = actors.user(userId);
        actors.apply(actor);
        requireSource(sourceId);
        FormatVersion copy = copyFrom == null ? null : getVersion(sourceId, copyFrom);
        int version;
        try {
            version = VersionErrors.translatingVersions(TABLE, () -> versioning.createDraft(TABLE, sourceId, actor));
            if (copy != null) {
                int lock = getVersion(sourceId, version).lockVersion();
                versioning.updateDraft(
                        TABLE,
                        sourceId,
                        version,
                        lock,
                        fileColumns(copy.fileKind(), copy.encoding(), copy.delimiter(), copy.matchColumnsBy()),
                        actor);
                repo.replaceSheets(sourceId, version, copy.sheets());
            }
        } catch (ConstraintViolationException | DataAccessException e) {
            throw UplErrors.toApi(e);
        }
        return getVersion(sourceId, version);
    }

    @Transactional
    public FormatVersion replaceDraft(long sourceId, int version, int lockVersion, DraftData d, long userId) {
        AuditActor actor = actors.user(userId);
        actors.apply(actor);
        requireSource(sourceId);
        lockDraft(sourceId, version);
        FileKind kind = d.fileKind() == null ? FileKind.XLSX : d.fileKind();
        MatchBy match = d.matchColumnsBy() == null ? MatchBy.HEADER : d.matchColumnsBy();
        try {
            versioning.updateDraft(
                    TABLE,
                    sourceId,
                    version,
                    lockVersion,
                    fileColumns(kind, d.encoding(), d.delimiter(), match),
                    actor);
            repo.replaceSheets(sourceId, version, d.sheets() == null ? List.of() : d.sheets());
        } catch (ConstraintViolationException | DataAccessException e) {
            throw UplErrors.toApi(e);
        }
        return getVersion(sourceId, version);
    }

    @Transactional
    public void publish(long sourceId, int version, LocalDate validFrom, long userId) {
        AuditActor actor = actors.user(userId);
        actors.apply(actor);
        requireSource(sourceId);
        if (validFrom == null) {
            throw ApiException.validation(
                    "error.upl.valid_from_required",
                    List.of(new FieldErrorItem("validFrom", VALID_FROM_REQUIRED, VALID_FROM_REQUIRED)));
        }
        lockDraft(sourceId, version);
        FormatVersion draft = getVersion(sourceId, version);
        List<FieldErrorItem> errors = validator.validate(draft);
        if (!errors.isEmpty()) {
            throw ApiException.validation("error.upl.format_invalid", errors);
        }
        try {
            versioning.publish(TABLE, sourceId, version, validFrom, null, actor);
        } catch (ConstraintViolationException | DataAccessException e) {
            throw UplErrors.toApi(e);
        }
    }

    private SourceView view(long id) {
        Source source = requireSource(id);
        SourceSummary summary = repo.findSummary(id)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.NOT_FOUND, "error.upl.source_not_found"));
        return new SourceView(source, summary.lastPublishedVersion(), summary.hasDraft());
    }

    private Source requireSource(long id) {
        return repo.findSource(id)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.NOT_FOUND, "error.upl.source_not_found"));
    }

    /** Locks the version row until the end of the transaction: concurrent edit and publication run in turn. */
    private void lockDraft(long sourceId, int version) {
        String status = repo.lockVersionStatus(sourceId, version)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.NOT_FOUND, "error.upl.fnd_version_unknown"));
        if (!Version.DRAFT.equals(status)) {
            throw ApiException.conflict(ErrorCode.CONFLICT, "error.upl.format_not_draft");
        }
    }

    private static Map<String, Object> fileColumns(FileKind kind, String encoding, String delimiter, MatchBy match) {
        Map<String, Object> columns = new HashMap<>();
        columns.put("file_kind", kind.db());
        columns.put("encoding", encoding);
        columns.put("delimiter", delimiter);
        columns.put("match_columns_by", match.db());
        return columns;
    }
}
