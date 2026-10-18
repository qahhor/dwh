package com.smartup24.cms.instance.md.service;

import com.smartup24.cms.instance.common.entity.report.EntityReportDatasetStore;
import com.smartup24.cms.instance.common.query.QueryComputedMeasure;
import com.smartup24.cms.instance.md.api.MdReportDatasetDtos.Dataset;
import com.smartup24.cms.instance.md.repository.MdReportDatasetRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** The report runtime reads administrators' datasets from md (ADR-0044); every read is current, nothing is cached. */
@Component
public class MdReportDatasetStore implements EntityReportDatasetStore {
    /** Writes the stored filter back into the DSL text the compiler reads (plan 10/10, item 3.11: the shared mapper). */
    private static final JsonMapper JSON = JsonMapper.shared();

    private final MdReportDatasetRepository repo;
    private final MdReportDatasetService service;

    public MdReportDatasetStore(MdReportDatasetRepository repo, MdReportDatasetService service) {
        this.repo = repo;
        this.service = service;
    }

    @Override
    public Optional<Definition> find(String code) {
        return repo.find(code).map(row -> definition(service.dataset(row)));
    }

    @Override
    public List<Definition> over(String entityCode) {
        return repo.over(entityCode, MdReportDatasetService.MAX_PER_ENTITY).stream()
                .map(row -> definition(service.dataset(row)))
                .toList();
    }

    private static Definition definition(Dataset dataset) {
        return new Definition(
                dataset.code(),
                Map.of(
                        "ru",
                        dataset.name().ru(),
                        "uz",
                        dataset.name().uz(),
                        "en",
                        dataset.name().en()),
                dataset.entity(),
                dataset.lines(),
                dataset.fields(),
                dataset.filter().isEmpty() ? null : JSON.writeValueAsString(dataset.filter()),
                dataset.measures().stream()
                        .map(measure -> new QueryComputedMeasure(
                                measure.key(),
                                Map.of(
                                        "ru", measure.labels().ru(),
                                        "uz", measure.labels().uz(),
                                        "en", measure.labels().en()),
                                measure.formula()))
                        .toList());
    }
}
