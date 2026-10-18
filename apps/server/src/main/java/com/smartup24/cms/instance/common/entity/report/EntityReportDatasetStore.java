package com.smartup24.cms.instance.common.entity.report;

import com.smartup24.cms.instance.common.query.QueryComputedMeasure;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The datasets an administrator defines for reports (ADR-0044), kept by the md module ({@code md_report_datasets}):
 * {@code common} knows no module, so md implements this. A report names one as {@code ds.<code>}; the dataset never
 * widens what a reader sees — the reader's view right, scope and field rights apply to its source when a report runs.
 */
public interface EntityReportDatasetStore {

    /** The prefix of an administrator's dataset among the datasets of a report. */
    String PREFIX = "ds.";

    /** The code of a dataset after its prefix. */
    Pattern CODE = Pattern.compile("[a-z][a-z0-9_]{0,39}");

    /** The dataset of this code, or empty. */
    Optional<Definition> find(String code);

    /** The datasets over this entity, at most a handful, in the order they were created. */
    List<Definition> over(String entityCode);

    /**
     * A dataset as its administrator defined it.
     *
     * @param names    its names in ru, uz and en
     * @param lines    the key of the table of document lines whose rows it counts, or null for the records
     * @param fields   the keys of the fields reports may use (lines' fields as {@code line<Key>})
     * @param filter   the fixed filter in the list's DSL, applied to every report, or null
     * @param measures the measures computed by formulas over aggregates ({@link QueryComputedMeasure})
     */
    record Definition(
            String code,
            Map<String, String> names,
            String entity,
            @Nullable String lines,
            List<String> fields,
            @Nullable String filter,
            List<QueryComputedMeasure> measures) {
        public Definition {
            names = Map.copyOf(names);
            fields = List.copyOf(fields);
            measures = List.copyOf(measures);
        }
    }
}
