package com.smartup24.cms.instance.studio.solution;

import com.smartup24.cms.instance.common.json.JsonColumns;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Labels;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.SolutionPackage;
import com.smartup24.cms.instance.studio.api.StudioPackageImportDtos.ImportItem;
import com.smartup24.cms.instance.studio.api.StudioPackageImportDtos.ImportSummary;
import com.smartup24.cms.instance.studio.api.StudioPackageImportDtos.ImportView;
import com.smartup24.cms.instance.studio.repository.StudioPackageRepository;
import com.smartup24.cms.instance.studio.repository.StudioPackageRepository.ImportRow;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** The JSON columns of an import and its views (ADR-0047). */
@Component
@Profile("!migrate")
public class SolutionViews {
    private static final TypeReference<List<ImportItem>> ITEMS = new TypeReference<>() {};
    private static final TypeReference<Map<String, List<Long>>> MAPPING = new TypeReference<>() {};

    private final JsonColumns columns;
    private final StudioPackageRepository imports;

    public SolutionViews(ObjectMapper json, StudioPackageRepository imports) {
        this.columns = new JsonColumns(json, "studio_package_imports");
        this.imports = imports;
    }

    public String write(Object value) {
        return columns.write(value);
    }

    public List<ImportItem> items(ImportRow row) {
        return columns.read(row.items(), ITEMS);
    }

    public Map<String, List<Long>> mapping(ImportRow row) {
        return columns.read(row.mapping(), MAPPING);
    }

    /** The package as it was verified on upload, without the suffix of its import. */
    public SolutionPackage pack(ImportRow row) {
        return columns.read(imports.packageOf(row.id()), SolutionPackage.class);
    }

    public ImportView view(ImportRow row) {
        SolutionPackage pack = pack(row);
        List<ImportItem> items = items(row);
        return new ImportView(
                row.id(),
                row.code(),
                row.version(),
                columns.read(row.title(), Labels.class),
                pack.description(),
                row.checksum(),
                row.source(),
                row.suffix(),
                row.status(),
                pack.producedBy(),
                pack.placeholders(),
                mapping(row),
                items,
                SolutionItems.counts(items),
                row.createdAt(),
                row.createdBy(),
                row.checkedAt(),
                row.appliedAt(),
                row.appliedBy());
    }

    public ImportSummary summary(ImportRow row) {
        return new ImportSummary(
                row.id(),
                row.code(),
                row.version(),
                columns.read(row.title(), Labels.class),
                row.source(),
                row.suffix(),
                row.status(),
                SolutionItems.counts(items(row)),
                row.createdAt(),
                row.appliedAt());
    }
}
