package com.smartup24.cms.instance.studio.automation;

import com.smartup24.cms.instance.common.entity.EntityRegistry;
import com.smartup24.cms.instance.common.entity.runtime.EntityReads;
import com.smartup24.cms.instance.kauth.service.KauthDeliveryAddresses;
import com.smartup24.cms.instance.md.service.MdPermissionService;
import com.smartup24.cms.instance.md.service.MdUserTexts;
import com.smartup24.cms.instance.ms.notify.service.MsNotificationService;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos.Right;
import com.smartup24.cms.instance.studio.api.StudioAutomationRunDtos.ActionResult;
import com.smartup24.cms.instance.studio.api.StudioAutomationRunDtos.Message;
import com.smartup24.cms.instance.studio.api.StudioAutomationRunDtos.Recipient;
import com.smartup24.cms.instance.studio.repository.StudioAutomationRepository;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The notifications of automations (ADR-0046). The recipients — the users of the record's user fields, its author, fixed
 * users and the holders of rights, at most {@value #MAX_RECIPIENTS} — are told only when active and allowed to see the
 * record now: the {@code view} right of its form and the record in their data scope (a deleted record: the right
 * alone). Each gets the texts in their language with the record's values; in-app at once, a letter or a Telegram
 * message through the notification outbox on a confirmed address only, with a key made of the run, the action, the
 * person and the channel, so a run delivers each once. A person who switched the notifications of automations off on a
 * channel is not told there. A dry run tells nobody: it names who would be told and what.
 */
@Component
public class AutomationNotices {
    /** The type of notification a person may switch off. */
    static final String EVENT_TYPE = "automation";

    static final int MAX_RECIPIENTS = 200;
    private static final String IN_APP = "in_app";

    private final MdPermissionService permissions;
    private final StudioAutomationRepository automations;
    private final EntityRegistry registry;
    private final EntityReads reads;
    private final MsNotificationService notifications;
    private final KauthDeliveryAddresses addresses;
    private final AutomationTexts texts;
    private final MdUserTexts users;
    private final String publicUrl;

    public AutomationNotices(
            MdPermissionService permissions,
            StudioAutomationRepository automations,
            EntityRegistry registry,
            EntityReads reads,
            MsNotificationService notifications,
            KauthDeliveryAddresses addresses,
            AutomationTexts texts,
            MdUserTexts users,
            @Value("${smc.public-url:}") String publicUrl) {
        this.permissions = permissions;
        this.automations = automations;
        this.registry = registry;
        this.reads = reads;
        this.notifications = notifications;
        this.addresses = addresses;
        this.texts = texts;
        this.users = users;
        this.publicUrl = publicUrl == null ? "" : publicUrl.strip().replaceAll("/+$", "");
    }

    /** Tells the recipients of {@code step}, or in a dry run names them. */
    ActionResult send(int index, AutomationSpec.Notify step, AutomationRun run, boolean gone) {
        Map<Long, String> active = automations.activeUsers(candidates(step, run, gone));
        Optional<EntityDefinition> entity = registry.find(run.form().code());
        List<Recipient> recipients = new ArrayList<>();
        Message first = null;
        for (Map.Entry<Long, String> person : active.entrySet()) {
            long user = person.getKey();
            if (!sees(user, run, entity.orElse(null), gone)) {
                recipients.add(new Recipient(user, person.getValue(), List.of(), "no_access"));
                continue;
            }
            String lang = users.language(user);
            Message message = new Message(texts.render(step.title(), lang, run), texts.render(step.body(), lang, run));
            if (first == null) first = message;
            List<String> reached = new ArrayList<>();
            for (String channel : step.channels()) {
                if (deliver(index, run, user, channel, message)) reached.add(channel);
            }
            recipients.add(new Recipient(user, person.getValue(), reached, reached.isEmpty() ? "no_channel" : null));
        }
        if (first == null) {
            String lang = users.language(run.authorId());
            first = new Message(texts.render(step.title(), lang, run), texts.render(step.body(), lang, run));
        }
        return new ActionResult(
                index,
                step.type(),
                run.dry() ? "would_apply" : "done",
                recipients.isEmpty() ? "no_recipients" : null,
                null,
                null,
                null,
                recipients,
                first,
                null);
    }

    /** The people a step names, in order and once each, at most {@value #MAX_RECIPIENTS}. */
    private Set<Long> candidates(AutomationSpec.Notify step, AutomationRun run, boolean gone) {
        Set<Long> people = new LinkedHashSet<>();
        for (String field : step.fields()) {
            Object value = run.record().get(field);
            String text = value == null ? "" : String.valueOf(value);
            // A user field keeps a user's key; any other value names nobody.
            if (text.matches("[0-9]{1,18}")) people.add(Long.parseLong(text));
        }
        if (step.author() && !gone)
            automations.recordAuthor(run.form().code(), run.recordId()).ifPresent(people::add);
        people.addAll(step.users());
        for (Right right : step.rights()) {
            people.addAll(permissions.holders(right.form(), Set.of(right.action()), MAX_RECIPIENTS));
        }
        Set<Long> bounded = new LinkedHashSet<>();
        for (Long person : people) {
            if (bounded.size() >= MAX_RECIPIENTS) break;
            bounded.add(person);
        }
        return bounded;
    }

    private boolean sees(long user, AutomationRun run, @Nullable EntityDefinition entity, boolean gone) {
        if (!permissions.activeHolder(user, run.form().code(), "view")) return false;
        return gone || (entity != null && reads.inScopeOf(entity, run.recordId(), user));
    }

    private boolean deliver(int index, AutomationRun run, long user, String channel, Message message) {
        if (!notifications.isNotificationEnabled(user, EVENT_TYPE, channel)) return false;
        String path = "/e/" + run.form().code() + "/" + run.recordId();
        if (IN_APP.equals(channel)) {
            if (!run.dry()) {
                notifications.sendInAppNotification(
                        user,
                        "info",
                        message.title(),
                        message.body(),
                        path,
                        "automation:" + run.automationId() + ":" + run.recordId());
            }
            return true;
        }
        if (!addresses.deliverable(channel)) return false;
        Optional<String> address = addresses.confirmedAddress(user, channel);
        if (address.isEmpty()) return false;
        if (run.dry()) return true;
        String link = publicUrl.isEmpty() ? null : publicUrl + path;
        Map<String, Object> payload = new LinkedHashMap<>();
        if ("email".equals(channel)) {
            payload.put("subject", message.title());
            String body = message.body();
            if (link != null) body += "\n\n" + users.text(user, "channel.automation.link", Map.of("link", link));
            payload.put("body", body);
        } else {
            payload.put(
                    "body",
                    AutomationTexts.markdown(message.title()) + "\n\n" + AutomationTexts.markdown(message.body()));
            if (link != null) {
                payload.put("buttonText", users.text(user, "channel.automation.open", Map.of()));
                payload.put("buttonUrl", link);
            }
        }
        notifications.enqueueExternalNotification(
                channel, address.get(), EVENT_TYPE, payload, key(run.key(), index, user, channel), null);
        return true;
    }

    /** The outbox key of a run, an action, a person and a channel: the same every time, so the outbox takes it once. */
    static UUID key(String run, int index, long user, String channel) {
        return UUID.nameUUIDFromBytes(
                ("automation:" + run + ":" + index + ":" + user + ":" + channel).getBytes(StandardCharsets.UTF_8));
    }
}
