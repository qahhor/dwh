package com.smartup24.cms.instance.studio.solution;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Labels;
import com.smartup24.cms.instance.studio.api.StudioPackageImportDtos.Counts;
import com.smartup24.cms.instance.studio.api.StudioPackageImportDtos.ImportItem;
import com.smartup24.cms.instance.studio.api.StudioPackageImportDtos.Problem;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs each item of a solution package in a savepoint of its own (ADR-0047): an item that fails rolls back alone and
 * is reported with its problems, the next items still run, so a check names every problem at once. Only a refusal of a
 * path that creates the item ({@link ApiException}) is an outcome; anything else is a fault that stops the run.
 */
@Component
@Profile("!migrate")
public class SolutionItems {
    public static final String CREATE = "create";
    public static final String UPGRADE = "upgrade";
    public static final String UNCHANGED = "unchanged";
    public static final String SKIP = "skip";
    public static final String REFUSED = "refused";

    /** What a step did to its item and what the importer should know. */
    public record Done(String outcome, List<String> notes) {
        public Done(String outcome) {
            this(outcome, List.of());
        }

        public Done with(String note) {
            List<String> all = new ArrayList<>(notes);
            all.add(note);
            return new Done(outcome, List.copyOf(all));
        }
    }

    private final TransactionTemplate savepoint;

    public SolutionItems(PlatformTransactionManager transactions) {
        this.savepoint = new TransactionTemplate(transactions);
        this.savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        this.savepoint.setName("solution-item");
    }

    public ImportItem run(String kind, String key, Labels title, Supplier<Done> step) {
        try {
            Done done = savepoint.execute(status -> step.get());
            if (done == null) throw new IllegalStateException("A step of a solution package returned nothing");
            return new ImportItem(kind, key, title, done.outcome(), List.of(), done.notes());
        } catch (ApiException refused) {
            return new ImportItem(kind, key, title, REFUSED, problems(refused), List.of());
        }
    }

    /** The item refused without running: a dependency failed or a placeholder is not mapped. */
    public static ImportItem refused(String kind, String key, Labels title, String message, Map<String, ?> params) {
        return new ImportItem(
                kind, key, title, REFUSED, List.of(new Problem(null, message, Map.copyOf(params))), List.of());
    }

    /** The problem itself and every problem of a part it names. */
    static List<Problem> problems(ApiException refused) {
        List<Problem> problems = new ArrayList<>();
        Map<String, Object> params = refused.getParams();
        problems.add(new Problem(null, refused.getMessageKey(), params.isEmpty() ? null : params));
        List<FieldErrorItem> parts = refused.getFieldErrors();
        if (parts != null) {
            for (FieldErrorItem part : parts) {
                String message = part.messageKey() == null ? part.message() : part.messageKey();
                problems.add(new Problem(part.field(), message, part.params()));
            }
        }
        return List.copyOf(problems);
    }

    public static Counts counts(List<ImportItem> items) {
        int create = 0;
        int upgrade = 0;
        int unchanged = 0;
        int skip = 0;
        int refused = 0;
        for (ImportItem item : items) {
            switch (item.outcome()) {
                case CREATE -> create++;
                case UPGRADE -> upgrade++;
                case UNCHANGED -> unchanged++;
                case SKIP -> skip++;
                default -> refused++;
            }
        }
        return new Counts(create, upgrade, unchanged, skip, refused);
    }

    /** The same name in each language: reports, mailings and automations have one name. */
    public static Labels same(String name) {
        return new Labels(name, name, name);
    }
}
