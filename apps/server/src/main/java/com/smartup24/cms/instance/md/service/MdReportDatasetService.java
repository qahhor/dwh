package com.smartup24.cms.instance.md.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.entity.report.EntityReportAdminSources;
import com.smartup24.cms.instance.common.entity.runtime.EntityGate;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.formula.FormulaException;
import com.smartup24.cms.instance.common.json.JsonColumns;
import com.smartup24.cms.instance.common.query.QueryComputedMeasure;
import com.smartup24.cms.instance.common.query.QueryField;
import com.smartup24.cms.instance.common.query.QueryList;
import com.smartup24.cms.instance.common.query.TimePage;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.common.web.Revisions;
import com.smartup24.cms.instance.md.api.MdReportDatasetDtos;
import com.smartup24.cms.instance.md.api.MdReportDatasetDtos.Dataset;
import com.smartup24.cms.instance.md.api.MdReportDatasetDtos.DatasetRequest;
import com.smartup24.cms.instance.md.api.MdReportDatasetDtos.Measure;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Labels;
import com.smartup24.cms.instance.md.repository.MdReportDatasetRepository;
import com.smartup24.cms.instance.md.repository.MdReportDatasetRepository.Row;
import com.smartup24.cms.instance.md.repository.MdReportDatasetRepository.Values;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The report datasets administrators define (ADR-0044): a source entity the administrator may view, optionally at the
 * grain of one table of its document lines, the fields reports may use, a fixed filter of the list's DSL and up to
 * eight measures computed by formulas over aggregates. Everything is checked against the source list as the
 * administrator sees it; a report reads the dataset through its own reader's rights. Every change names the revision it
 * was made from ({@code If-Match}) and is audited. A saved report over a deleted or changed dataset is no longer offered
 * when its fields are gone.
 */
@Service
public class MdReportDatasetService {
    public static final int MAX_DATASETS = 100;
    public static final int MAX_PER_ENTITY = 8;
    private static final String TABLE = "md_report_datasets";
    private static final String INVALID = "REPORT_DATASET_INVALID";
    private static final Pattern CODE = Pattern.compile(MdReportDatasetDtos.CODE);
    private static final List<String> AUDITED =
            List.of("code", "name", "entity_code", "lines", "fields", "filter", "measures");

    private final MdReportDatasetRepository repo;
    private final EntityGate gate;
    private final EntityReportAdminSources sources;
    private final AuditLogService audit;
    private final ObjectMapper json;
    private final JsonColumns columns;

    public MdReportDatasetService(
            MdReportDatasetRepository repo,
            EntityGate gate,
            EntityReportAdminSources sources,
            AuditLogService audit,
            ObjectMapper json) {
        this.repo = repo;
        this.gate = gate;
        this.sources = sources;
        this.audit = audit;
        this.json = json;
        this.columns = new JsonColumns(json, TABLE);
    }

    @Transactional(readOnly = true)
    public KeysetPage<Dataset> list(@Nullable Integer limit, @Nullable String cursor) {
        TimePage page = TimePage.of(limit, cursor, 50, 100);
        return page.page(repo.page(page), row -> new TimePage.Position(row.createdAt(), row.id()))
                .map(this::dataset);
    }

    @Transactional(readOnly = true)
    public Dataset get(String code) {
        return dataset(row(code));
    }

    /** The dataset of the code, if there is one: what a solution package looks for before it creates it (ADR-0047). */
    @Transactional(readOnly = true)
    public Optional<Dataset> find(String code) {
        return CODE.matcher(code).matches() ? repo.find(code).map(this::dataset) : Optional.empty();
    }

    /** Every field a dataset over the entity may offer, as its administrator sees them; with lines, theirs too. */
    @Transactional(readOnly = true)
    public QueryList fields(String entity, @Nullable String lines) {
        return sources.full(source(entity), lines);
    }

    @Transactional
    public Dataset create(DatasetRequest request) {
        String code = request.code();
        if (code == null || !CODE.matcher(code).matches()) {
            throw invalid(List.of(FieldErrorItem.keyed("code", INVALID, "error.md.report_dataset_code")));
        }
        Values values = check(request);
        repo.lock();
        if (repo.find(code).isPresent()) {
            throw ApiException.conflict(ErrorCode.CONFLICT, "error.md.report_dataset_exists", Map.of("code", code));
        }
        if (repo.count() >= MAX_DATASETS || repo.countOver(values.entity()) >= MAX_PER_ENTITY) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "error.md.report_dataset_quota",
                    Map.of("max", MAX_DATASETS, "perEntity", MAX_PER_ENTITY));
        }
        long id = repo.insert(code, values, Objects.requireNonNull(SecurityContext.getCurrentUserId(), "user"));
        audit.logChange(TABLE, Long.toString(id), "I", AUDITED, Map.of(), audited(code, values));
        return get(code);
    }

    @Transactional
    public Dataset update(String code, @Nullable String ifMatch, DatasetRequest request) {
        long revision = Revisions.required(ifMatch);
        Row before = row(code);
        Values values = check(request);
        if (!values.entity().equals(before.entity()) && repo.countOver(values.entity()) >= MAX_PER_ENTITY) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "error.md.report_dataset_quota",
                    Map.of("max", MAX_DATASETS, "perEntity", MAX_PER_ENTITY));
        }
        long user = Objects.requireNonNull(SecurityContext.getCurrentUserId(), "user");
        if (!repo.update(before.id(), revision, values, user)) throw Revisions.conflict();
        audit.logChange(
                TABLE, Long.toString(before.id()), "U", AUDITED, audited(code, values(before)), audited(code, values));
        return get(code);
    }

    @Transactional
    public void delete(String code, @Nullable String ifMatch) {
        long revision = Revisions.required(ifMatch);
        Row before = row(code);
        if (!repo.delete(before.id(), revision)) throw Revisions.conflict();
        audit.logChange(TABLE, Long.toString(before.id()), "D", AUDITED, audited(code, values(before)), null);
    }

    /** The values to store, every problem at its part: names, source, fields, filter and measures. */
    private Values check(DatasetRequest request) {
        List<FieldErrorItem> errors = new ArrayList<>();
        String[][] names = {
            {"ru", request.name().ru()},
            {"uz", request.name().uz()},
            {"en", request.name().en()}
        };
        for (String[] name : names) {
            if (name[1] == null || !MdSimpleFormDeclarations.validLabel(name[1])) {
                errors.add(FieldErrorItem.keyed("name." + name[0], "label", "error.md.simple_form_label"));
            }
        }
        EntityDefinition root = gate.findViewable(String.valueOf(request.entity()))
                .filter(entity -> entity.model() != null && entity.listCode() != null)
                .orElse(null);
        if (root == null) {
            errors.add(FieldErrorItem.keyed("entity", INVALID, "error.md.report_dataset_entity"));
            throw invalid(errors);
        }
        QueryList full;
        try {
            full = sources.full(root, request.lines());
        } catch (ApiException missing) {
            if (missing.getErrorCode() != ErrorCode.NOT_FOUND) throw missing;
            errors.add(FieldErrorItem.keyed("lines", INVALID, "error.md.report_dataset_lines"));
            throw invalid(errors);
        }
        List<String> fields = request.fields() == null ? List.of() : request.fields();
        if (fields.isEmpty() || fields.size() > MdReportDatasetDtos.MAX_FIELDS) {
            errors.add(FieldErrorItem.keyed("fields", INVALID, "error.md.report_dataset_fields"));
        }
        JsonNode filter = request.filter() == null ? json.createArrayNode() : json.valueToTree(request.filter());
        if (!filter.isArray())
            errors.add(FieldErrorItem.keyed("filter", INVALID, "error.common.field_filter_not_array"));
        else errors.addAll(sources.check(full, request.lines(), fields, filter.isEmpty() ? null : filter.toString()));
        List<Measure> measures = request.measures() == null ? List.of() : request.measures();
        measures(full, fields, measures, errors);
        if (!errors.isEmpty()) throw invalid(errors);
        return new Values(
                request.name(),
                root.code(),
                request.lines(),
                columns.write(fields),
                columns.write(filter),
                columns.write(measures));
    }

    /** The computed measures: keys apart from the fields, names in three languages, formulas over the aggregates. */
    private static void measures(
            QueryList full, List<String> fields, List<Measure> measures, List<FieldErrorItem> errors) {
        if (measures.size() > MdReportDatasetDtos.MAX_MEASURES) {
            errors.add(FieldErrorItem.keyed("measures", INVALID, "error.md.report_dataset_measures"));
            return;
        }
        List<QueryField> offered = new ArrayList<>();
        for (String key : fields) full.viewerField(key).ifPresent(offered::add);
        Set<String> keys = new HashSet<>(fields);
        for (int i = 0; i < measures.size(); i++) {
            Measure measure = measures.get(i);
            String at = "measures[" + i + "]";
            if (measure.key() == null
                    || !QueryComputedMeasure.KEY.matcher(measure.key()).matches()
                    || !keys.add(measure.key())) {
                errors.add(FieldErrorItem.keyed(at + ".key", INVALID, "error.md.report_dataset_measure_key"));
                continue;
            }
            Labels labels = measure.labels();
            if (labels == null
                    || !MdSimpleFormDeclarations.validLabel(labels.ru())
                    || !MdSimpleFormDeclarations.validLabel(labels.uz())
                    || !MdSimpleFormDeclarations.validLabel(labels.en())) {
                errors.add(FieldErrorItem.keyed(at + ".labels", "label", "error.md.simple_form_label"));
            }
            try {
                new QueryComputedMeasure(measure.key(), Map.of(), String.valueOf(measure.formula())).compile(offered);
            } catch (FormulaException problem) {
                errors.add(problem.fieldError(at + ".formula"));
            }
        }
    }

    private EntityDefinition source(String entity) {
        return gate.findViewable(entity)
                .filter(found -> found.model() != null && found.listCode() != null)
                .orElseThrow(() ->
                        invalid(List.of(FieldErrorItem.keyed("entity", INVALID, "error.md.report_dataset_entity"))));
    }

    private Row row(String code) {
        if (!CODE.matcher(code).matches()) throw notFound();
        return repo.find(code).orElseThrow(MdReportDatasetService::notFound);
    }

    Dataset dataset(Row row) {
        return new Dataset(
                row.code(),
                row.name(),
                row.entity(),
                row.lines(),
                columns.read(row.fields(), new TypeReference<List<String>>() {}),
                columns.read(row.filter(), new TypeReference<List<Map<String, Object>>>() {}),
                columns.read(row.measures(), new TypeReference<List<Measure>>() {}),
                row.revision(),
                row.createdAt(),
                row.modifiedAt());
    }

    private static Values values(Row row) {
        return new Values(row.name(), row.entity(), row.lines(), row.fields(), row.filter(), row.measures());
    }

    private static Map<String, Object> audited(String code, Values values) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("code", code);
        row.put(
                "name",
                Map.of(
                        "ru",
                        values.name().ru(),
                        "uz",
                        values.name().uz(),
                        "en",
                        values.name().en()));
        row.put("entity_code", values.entity());
        row.put("lines", values.lines());
        row.put("fields", values.fields());
        row.put("filter", values.filter());
        row.put("measures", values.measures());
        return row;
    }

    private static ApiException invalid(List<FieldErrorItem> errors) {
        return ApiException.validation("error.md.report_dataset_invalid", errors);
    }

    private static ApiException notFound() {
        return ApiException.notFound(ErrorCode.NOT_FOUND, "error.md.report_dataset_not_found");
    }
}
