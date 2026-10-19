package com.smartup24.cms.instance.studio.solution;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.entity.report.EntityReportDatasetStore;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.DeliveryPackage;
import com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos.Right;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.Action;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.Grant;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.PackageAutomation;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.PackageDataset;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.PackageList;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.PackageReport;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.Permission;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.Recipients;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.SolutionPackage;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A second copy of a solution under other codes (ADR-0047): the suffix goes after the code of every form, list and
 * dataset of the package and after every reference to them — the targets of fields and columns, the source of a
 * dataset, the list and the dataset of a report, the forms of automations and of grants. The forms are prepared again,
 * so their checksums are the server's; the codes of list items and of fields do not change.
 */
@Component
public class SolutionSuffix {
    private static final int MAX_FORM_NAME = 20;
    private static final int MAX_CODE = 40;

    private final ObjectMapper json;
    private final MdSimpleFormDeclarations declarations;

    public SolutionSuffix(ObjectMapper json, MdSimpleFormDeclarations declarations) {
        this.json = json;
        this.declarations = declarations;
    }

    /** The package under the suffix; without a suffix, the package itself. */
    public SolutionPackage apply(SolutionPackage pack, @Nullable String suffix) {
        if (suffix == null) return pack;
        if (!suffix.matches(StudioPackageDtos.SUFFIX)) throw refused();
        Names names = new Names(pack, suffix);
        return new SolutionPackage(
                pack.formatVersion(),
                pack.kind(),
                pack.code(),
                pack.version(),
                pack.title(),
                pack.description(),
                pack.requires(),
                pack.producedBy(),
                pack.placeholders(),
                pack.lists().stream()
                        .map(list -> new PackageList(names.list(list.code()), list.name(), list.items()))
                        .toList(),
                pack.forms().stream().map(names::form).toList(),
                pack.datasets().stream().map(names::dataset).toList(),
                pack.reports().stream().map(names::report).toList(),
                pack.mailings(),
                pack.automations().stream().map(names::automation).toList(),
                pack.grants().stream().map(names::grant).toList(),
                pack.checksum());
    }

    private static ApiException refused() {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "error.studio.solution_suffix");
    }

    /** The codes of one package and their new names. */
    private final class Names {
        private final String suffix;
        private final Set<String> forms = new HashSet<>();
        private final Set<String> lists = new HashSet<>();
        private final Set<String> datasets = new HashSet<>();

        Names(SolutionPackage pack, String suffix) {
            this.suffix = suffix;
            pack.forms()
                    .forEach(form ->
                            forms.add("studio." + form.declaration().entity().name()));
            pack.lists().forEach(list -> lists.add(list.code()));
            pack.datasets().forEach(dataset -> datasets.add(dataset.code()));
        }

        String list(String code) {
            if (!lists.contains(code)) return code;
            if (code.length() + suffix.length() > MAX_CODE) throw refused();
            return code + suffix;
        }

        String formCode(@Nullable String code) {
            if (code == null || !forms.contains(code)) return code == null ? "" : code;
            if (code.length() - "studio.".length() + suffix.length() > MAX_FORM_NAME) throw refused();
            return code + suffix;
        }

        String datasetCode(String code) {
            if (!datasets.contains(code)) return code;
            if (code.length() + suffix.length() > MAX_CODE) throw refused();
            return code + suffix;
        }

        DeliveryPackage form(DeliveryPackage form) {
            ObjectNode declaration = (ObjectNode) json.valueToTree(form.declaration());
            ObjectNode entity = (ObjectNode) declaration.get("entity");
            entity.put(
                    "name", formCode("studio." + entity.get("name").asString()).substring("studio.".length()));
            targets(declaration.get("fields"));
            declaration.path("lines").forEach(table -> targets(table.get("fields")));
            return declarations.prepare(json.writeValueAsBytes(declaration));
        }

        private void targets(JsonNode fields) {
            for (JsonNode field : fields) {
                if (!(field.get("target") instanceof ObjectNode target)) continue;
                String code = target.path("code").asString();
                target.put("code", "list".equals(target.path("kind").asString()) ? list(code) : formCode(code));
            }
        }

        PackageDataset dataset(PackageDataset dataset) {
            return new PackageDataset(
                    datasetCode(dataset.code()),
                    dataset.name(),
                    formCode(dataset.entity()),
                    dataset.lines(),
                    dataset.fields(),
                    dataset.filter(),
                    dataset.measures());
        }

        PackageReport report(PackageReport report) {
            ObjectNode state = (ObjectNode) report.state().deepCopy();
            String dataset = state.path("dataset").asString("");
            if (dataset.startsWith(EntityReportDatasetStore.PREFIX)) {
                state.put(
                        "dataset",
                        EntityReportDatasetStore.PREFIX
                                + datasetCode(dataset.substring(EntityReportDatasetStore.PREFIX.length())));
            }
            return new PackageReport(report.key(), report.name(), formCode(report.list()), state, report.share());
        }

        PackageAutomation automation(PackageAutomation automation) {
            return new PackageAutomation(
                    automation.key(),
                    formCode(automation.form()),
                    automation.name(),
                    automation.trigger(),
                    automation.condition(),
                    automation.actions().stream().map(this::action).toList(),
                    automation.timeZone());
        }

        private Action action(Action action) {
            Recipients recipients = action.recipients();
            Recipients renamed = recipients == null || recipients.rights() == null
                    ? recipients
                    : new Recipients(
                            recipients.fields(),
                            recipients.author(),
                            recipients.users(),
                            recipients.rights().stream()
                                    .map(right -> new Right(formCode(right.form()), right.action()))
                                    .toList());
            return new Action(
                    action.type(),
                    action.values(),
                    action.form() == null ? null : formCode(action.form()),
                    action.transition(),
                    action.comment(),
                    action.channels(),
                    renamed,
                    action.title(),
                    action.body());
        }

        Grant grant(Grant grant) {
            List<Permission> permissions = grant.permissions().stream()
                    .map(permission -> new Permission(formCode(permission.form()), permission.action()))
                    .toList();
            return new Grant(grant.role(), permissions);
        }
    }
}
