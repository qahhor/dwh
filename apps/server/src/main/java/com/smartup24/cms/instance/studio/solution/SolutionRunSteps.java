package com.smartup24.cms.instance.studio.solution;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.web.Revisions;
import com.smartup24.cms.instance.md.api.MdRoleDtos.RolePermission;
import com.smartup24.cms.instance.md.service.MdRoleService;
import com.smartup24.cms.instance.report.api.ReportMailingDtos.MailingRequest;
import com.smartup24.cms.instance.report.service.ReportMailingService;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos.AutomationRequest;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.Action;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.Grant;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.PackageAutomation;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.PackageMailing;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.Recipients;
import com.smartup24.cms.instance.studio.repository.StudioPackageRepository;
import com.smartup24.cms.instance.studio.repository.StudioPackageRepository.ObjectRow;
import com.smartup24.cms.instance.studio.service.StudioAutomationService;
import com.smartup24.cms.instance.studio.solution.SolutionItems.Done;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The mailings, automations and grants of a solution package (ADR-0047). Mailings and automations are created or
 * changed paused: an administrator reviews and switches each on, and an automation then runs as that person. One the
 * package did not change since an earlier application stays as it is, switched on or not. A grant adds the rights of
 * the package's forms to the role its placeholder is mapped to, and only for an importer who may grant rights.
 */
@Component
public class SolutionRunSteps {
    static final String MAILING = "mailing";
    static final String AUTOMATION = "automation";
    static final String PAUSED = "studio.packages.note.paused";

    private final ReportMailingService mailings;
    private final StudioAutomationService automations;
    private final MdRoleService roles;
    private final StudioPackageRepository objects;
    private final SolutionFormat format;

    public SolutionRunSteps(
            ReportMailingService mailings,
            StudioAutomationService automations,
            MdRoleService roles,
            StudioPackageRepository objects,
            SolutionFormat format) {
        this.mailings = mailings;
        this.automations = automations;
        this.roles = roles;
        this.objects = objects;
        this.format = format;
    }

    public Done mailing(SolutionRun run, PackageMailing mailing) {
        Long report = run.reports().get(mailing.report());
        if (report == null)
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "error.studio.solution_dependency", Map.of("key", mailing.report()));
        List<String> offered = mailings.options().channels();
        List<String> channels =
                mailing.channels().stream().filter(offered::contains).toList();
        if (channels.isEmpty()) throw new ApiException(ErrorCode.VALIDATION_FAILED, "error.studio.solution_channels");
        MailingRequest request = new MailingRequest(
                report,
                mailing.name(),
                run.users(mailing.recipients()),
                channels,
                mailing.attachXlsx(),
                mailing.includeLink(),
                mailing.schedule(),
                mailing.parameters(),
                mailing.condition(),
                false);
        String digest = format.digest(mailing);
        Optional<ObjectRow> remembered = objects.object(run.scope(), MAILING, mailing.key());
        var earlier = remembered.flatMap(row -> mailings.find(row.objectId()));
        Done done;
        long id;
        if (earlier.isEmpty()) {
            id = mailings.create(request).id();
            done = new Done(SolutionItems.CREATE).with(PAUSED);
        } else if (digest.equals(remembered.get().checksum())) {
            id = earlier.get().id();
            done = new Done(SolutionItems.UNCHANGED);
        } else {
            id = mailings.update(
                            earlier.get().id(), Revisions.etag(earlier.get().revision()), request)
                    .id();
            done = new Done(SolutionItems.UPGRADE).with(PAUSED);
        }
        if (channels.size() < mailing.channels().size()) done = done.with("studio.packages.note.channels");
        if (!run.mapped(mailing.recipients())) done = done.with("studio.packages.note.unmapped");
        if (run.apply()) objects.remember(run.scope(), MAILING, mailing.key(), id, digest, run.importId());
        return done;
    }

    public Done automation(SolutionRun run, PackageAutomation automation) {
        SolutionCatalogSteps.require("studio.automations", "manage");
        AutomationRequest request = new AutomationRequest(
                automation.form(),
                automation.name(),
                false,
                automation.trigger(),
                automation.condition(),
                automation.actions().stream().map(action -> action(run, action)).toList(),
                automation.timeZone());
        String digest = format.digest(automation);
        Optional<ObjectRow> remembered = objects.object(run.scope(), AUTOMATION, automation.key());
        var earlier = remembered.flatMap(row -> automations.find(row.objectId()));
        Done done;
        long id;
        if (earlier.isEmpty()) {
            id = automations.create(request).id();
            done = new Done(SolutionItems.CREATE).with(PAUSED);
        } else if (digest.equals(remembered.get().checksum())) {
            id = earlier.get().id();
            done = new Done(SolutionItems.UNCHANGED);
        } else {
            id = automations
                    .update(earlier.get().id(), Revisions.etag(earlier.get().revision()), request)
                    .id();
            done = new Done(SolutionItems.UPGRADE).with(PAUSED);
        }
        boolean unmapped = automation.actions().stream()
                .anyMatch(action -> action.recipients() != null
                        && action.recipients().users() != null
                        && !run.mapped(action.recipients().users()));
        if (unmapped) done = done.with("studio.packages.note.unmapped");
        if (run.apply()) objects.remember(run.scope(), AUTOMATION, automation.key(), id, digest, run.importId());
        return done;
    }

    /** The rights of the grant for the mapped role; an unmapped role placeholder grants nothing. */
    public Done grant(SolutionRun run, Grant grant) {
        Long role = run.role(grant.role());
        if (role == null) return new Done(SolutionItems.SKIP).with("studio.packages.note.unmapped");
        SolutionCatalogSteps.require("md.roles", "grant");
        List<RolePermission> permissions = grant.permissions().stream()
                .map(permission -> new RolePermission(permission.form(), permission.action()))
                .toList();
        return new Done(roles.grant(role, permissions) ? SolutionItems.UPGRADE : SolutionItems.UNCHANGED);
    }

    private static StudioAutomationDtos.Action action(SolutionRun run, Action action) {
        Recipients recipients = action.recipients();
        StudioAutomationDtos.Recipients resolved = recipients == null
                ? null
                : new StudioAutomationDtos.Recipients(
                        recipients.fields(), recipients.author(), run.users(recipients.users()), recipients.rights());
        return new StudioAutomationDtos.Action(
                action.type(),
                action.values(),
                action.form(),
                action.transition(),
                action.comment(),
                action.channels(),
                resolved,
                action.title(),
                action.body());
    }
}
