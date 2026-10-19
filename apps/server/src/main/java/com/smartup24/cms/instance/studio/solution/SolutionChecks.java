package com.smartup24.cms.instance.studio.solution;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.md.api.MdReportDatasetDtos;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.DeliveryPackage;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Labels;
import com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos;
import com.smartup24.cms.instance.studio.api.StudioListDtos;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.Action;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.Grant;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.ListItem;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.PackageAutomation;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.PackageDataset;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.PackageList;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.PackageMailing;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.PackageReport;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.Permission;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.Placeholder;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.SolutionPackage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The structure of a mapped solution package (ADR-0047): every required part present, codes and keys well formed and
 * unique, counts bounded and every reference between the parts resolved — a mailing to a report of the package, a
 * placeholder of the right kind, a grant to the package's own forms. What each part means is checked by the path that
 * creates it.
 */
final class SolutionChecks {
    private static final Pattern CODE = Pattern.compile(StudioPackageDtos.CODE);
    private static final Pattern VERSION = Pattern.compile(StudioPackageDtos.VERSION);
    private static final Pattern KEY = Pattern.compile(StudioPackageDtos.KEY);
    private static final Pattern LIST = Pattern.compile(StudioListDtos.CODE);
    private static final Pattern ITEM = Pattern.compile(StudioListDtos.ITEM_CODE);
    private static final Pattern DATASET = Pattern.compile(MdReportDatasetDtos.CODE);
    static final String ROLE = "role";
    static final int MAX_DESCRIPTION = 500;
    static final String USERS = "users";

    private final List<FieldErrorItem> errors = new ArrayList<>();
    private final Map<String, String> placeholders = new HashMap<>();

    private SolutionChecks() {}

    static void require(SolutionPackage pack) {
        SolutionChecks checks = new SolutionChecks();
        checks.head(pack);
        checks.placeholders(pack.placeholders());
        checks.lists(pack.lists());
        Set<String> forms = checks.forms(
                pack.forms(), pack.requires() == null ? 0 : pack.requires().simpleFormFormat());
        checks.datasets(pack.datasets());
        Set<String> reports = checks.reports(pack.reports());
        checks.mailings(pack.mailings(), reports);
        checks.automations(pack.automations());
        checks.grants(pack.grants(), forms);
        if (!checks.errors.isEmpty()) throw ApiException.validation("error.studio.solution_invalid", checks.errors);
    }

    private void head(SolutionPackage pack) {
        matches(pack.code(), CODE, "code");
        matches(pack.version(), VERSION, "version");
        labels(pack.title(), "title");
        if (pack.description() != null) description(pack.description());
        if (pack.requires() == null || pack.requires().simpleFormFormat() < 1) fail("requires");
        if (pack.producedBy() == null
                || pack.producedBy().isBlank()
                || pack.producedBy().length() > 64) fail("producedBy");
    }

    private void placeholders(@Nullable List<Placeholder> list) {
        if (bounded(list, StudioPackageDtos.MAX_PLACEHOLDERS, "placeholders")) return;
        for (int i = 0; i < list.size(); i++) {
            Placeholder placeholder = list.get(i);
            String at = "placeholders[" + i + "]";
            if (placeholder == null) {
                fail(at);
                continue;
            }
            if (!matches(placeholder.key(), KEY, at + ".key")) continue;
            if (!ROLE.equals(placeholder.kind()) && !USERS.equals(placeholder.kind())) fail(at + ".kind");
            labels(placeholder.labels(), at + ".labels");
            if (placeholders.put(placeholder.key(), String.valueOf(placeholder.kind())) != null) fail(at + ".key");
        }
    }

    private void lists(@Nullable List<PackageList> lists) {
        if (bounded(lists, StudioPackageDtos.MAX_LISTS, "lists")) return;
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < lists.size(); i++) {
            PackageList list = lists.get(i);
            String at = "lists[" + i + "]";
            if (list == null) {
                fail(at);
                continue;
            }
            if (matches(list.code(), LIST, at + ".code") && !codes.add(list.code())) fail(at + ".code");
            labels(list.name(), at + ".name");
            if (bounded(list.items(), StudioListDtos.MAX_ITEMS, at + ".items")) continue;
            Set<String> items = new HashSet<>();
            for (int j = 0; j < list.items().size(); j++) {
                ListItem item = list.items().get(j);
                String place = at + ".items[" + j + "]";
                if (item == null) {
                    fail(place);
                    continue;
                }
                if (matches(item.code(), ITEM, place + ".code") && !items.add(item.code())) fail(place + ".code");
                labels(item.name(), place + ".name");
                if (item.sortOrder() < 1 || item.sortOrder() > 9999) fail(place + ".sortOrder");
            }
        }
    }

    private Set<String> forms(List<DeliveryPackage> forms, int format) {
        Set<String> codes = new HashSet<>();
        if (forms.size() > StudioPackageDtos.MAX_FORMS) fail("forms");
        for (int i = 0; i < forms.size(); i++) {
            var declaration = forms.get(i).declaration();
            if (!codes.add("studio." + declaration.entity().name())) fail("forms[" + i + "]");
            if (declaration.formatVersion() > format) fail("forms[" + i + "].declaration.formatVersion");
        }
        return codes;
    }

    private void datasets(@Nullable List<PackageDataset> datasets) {
        if (bounded(datasets, StudioPackageDtos.MAX_DATASETS, "datasets")) return;
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < datasets.size(); i++) {
            PackageDataset dataset = datasets.get(i);
            String at = "datasets[" + i + "]";
            if (dataset == null) {
                fail(at);
                continue;
            }
            if (matches(dataset.code(), DATASET, at + ".code") && !codes.add(dataset.code())) fail(at + ".code");
            labels(dataset.name(), at + ".name");
            if (dataset.entity() == null) fail(at + ".entity");
            if (dataset.fields() == null) fail(at + ".fields");
            if (dataset.filter() == null) fail(at + ".filter");
            if (dataset.measures() == null) fail(at + ".measures");
        }
    }

    private Set<String> reports(@Nullable List<PackageReport> reports) {
        Set<String> keys = new HashSet<>();
        if (bounded(reports, StudioPackageDtos.MAX_REPORTS, "reports")) return keys;
        for (int i = 0; i < reports.size(); i++) {
            PackageReport report = reports.get(i);
            String at = "reports[" + i + "]";
            if (report == null) {
                fail(at);
                continue;
            }
            if (matches(report.key(), KEY, at + ".key") && !keys.add(report.key())) fail(at + ".key");
            text(report.name(), 80, at + ".name");
            if (report.list() == null) fail(at + ".list");
            if (report.state() == null || !report.state().isObject()) fail(at + ".state");
            if (bounded(report.share(), 20, at + ".share")) continue;
            for (int j = 0; j < report.share().size(); j++)
                placeholder(report.share().get(j), ROLE, at + ".share[" + j + "]");
        }
        return keys;
    }

    private void mailings(@Nullable List<PackageMailing> mailings, Set<String> reports) {
        if (bounded(mailings, StudioPackageDtos.MAX_MAILINGS, "mailings")) return;
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < mailings.size(); i++) {
            PackageMailing mailing = mailings.get(i);
            String at = "mailings[" + i + "]";
            if (mailing == null) {
                fail(at);
                continue;
            }
            if (matches(mailing.key(), KEY, at + ".key") && !keys.add(mailing.key())) fail(at + ".key");
            if (!reports.contains(String.valueOf(mailing.report()))) fail(at + ".report");
            text(mailing.name(), 120, at + ".name");
            placeholder(mailing.recipients(), USERS, at + ".recipients");
            if (mailing.channels() == null || mailing.channels().isEmpty()) fail(at + ".channels");
            if (mailing.schedule() == null) fail(at + ".schedule");
            if (mailing.condition() == null) fail(at + ".condition");
            if (mailing.parameters() == null) fail(at + ".parameters");
        }
    }

    private void automations(@Nullable List<PackageAutomation> automations) {
        if (bounded(automations, StudioPackageDtos.MAX_AUTOMATIONS, "automations")) return;
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < automations.size(); i++) {
            PackageAutomation automation = automations.get(i);
            String at = "automations[" + i + "]";
            if (automation == null) {
                fail(at);
                continue;
            }
            if (matches(automation.key(), KEY, at + ".key") && !keys.add(automation.key())) fail(at + ".key");
            if (automation.form() == null) fail(at + ".form");
            text(automation.name(), StudioAutomationDtos.MAX_NAME, at + ".name");
            if (automation.trigger() == null) fail(at + ".trigger");
            if (automation.timeZone() == null) fail(at + ".timeZone");
            List<Action> actions = automation.actions();
            if (actions == null || actions.isEmpty() || actions.size() > StudioAutomationDtos.MAX_ACTIONS) {
                fail(at + ".actions");
                continue;
            }
            for (int j = 0; j < actions.size(); j++) {
                Action action = actions.get(j);
                if (action == null || action.type() == null) fail(at + ".actions[" + j + "]");
                else if (action.recipients() != null && action.recipients().users() != null)
                    placeholder(action.recipients().users(), USERS, at + ".actions[" + j + "].recipients.users");
            }
        }
    }

    private void grants(@Nullable List<Grant> grants, Set<String> forms) {
        if (bounded(grants, StudioPackageDtos.MAX_GRANTS, "grants")) return;
        for (int i = 0; i < grants.size(); i++) {
            Grant grant = grants.get(i);
            String at = "grants[" + i + "]";
            if (grant == null) {
                fail(at);
                continue;
            }
            placeholder(grant.role(), ROLE, at + ".role");
            if (bounded(grant.permissions(), 100, at + ".permissions")) continue;
            if (grant.permissions().isEmpty()) {
                fail(at + ".permissions");
                continue;
            }
            for (int j = 0; j < grant.permissions().size(); j++) {
                Permission permission = grant.permissions().get(j);
                if (permission == null
                        || !forms.contains(String.valueOf(permission.form()))
                        || permission.action() == null
                        || !permission.action().matches("[a-z][a-z0-9_]{0,39}")) fail(at + ".permissions[" + j + "]");
            }
        }
    }

    private void placeholder(@Nullable String key, String kind, String at) {
        if (key == null || !kind.equals(placeholders.get(key)))
            errors.add(FieldErrorItem.keyed(
                    at, "solution_invalid", "error.studio.solution_placeholder", Map.of("key", String.valueOf(key))));
    }

    /** Whether the list is missing or too long, reported at its place; a present, bounded list returns false. */
    private boolean bounded(@Nullable List<?> list, int max, String at) {
        if (list != null && list.size() <= max) return false;
        fail(at);
        return true;
    }

    private boolean matches(@Nullable String value, Pattern pattern, String at) {
        if (value != null && pattern.matcher(value).matches()) return true;
        fail(at);
        return false;
    }

    private void labels(@Nullable Labels labels, String at) {
        if (labels == null || !valid(labels.ru()) || !valid(labels.uz()) || !valid(labels.en())) fail(at);
    }

    /** A description: up to 500 characters in each language, without control characters or spaces at its edges. */
    private void description(Labels description) {
        for (String text : new String[] {description.ru(), description.uz(), description.en()}) {
            if (text == null
                    || text.isBlank()
                    || text.length() > MAX_DESCRIPTION
                    || !text.strip().equals(text)
                    || text.chars().anyMatch(Character::isISOControl)) {
                fail("description");
                return;
            }
        }
    }

    private void text(@Nullable String value, int max, String at) {
        if (value == null || value.isBlank() || value.length() > max) fail(at);
    }

    private static boolean valid(@Nullable String label) {
        return label != null && MdSimpleFormDeclarations.validLabel(label);
    }

    private void fail(String at) {
        errors.add(FieldErrorItem.keyed(at, "solution_invalid", "error.studio.solution_part"));
    }
}
