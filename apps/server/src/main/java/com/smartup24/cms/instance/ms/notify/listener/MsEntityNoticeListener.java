package com.smartup24.cms.instance.ms.notify.listener;

import com.smartup24.cms.instance.common.entity.EntityNotice;
import com.smartup24.cms.instance.md.service.MdUserTexts;
import com.smartup24.cms.instance.ms.notify.pref.MsNotifyPref;
import com.smartup24.cms.instance.ms.notify.service.MsNotificationService;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Turns the notice of a record that waits for people's step (ADR-0043) into an in-app notification for each of them, in
 * the recipient's language: the texts come from the catalog, the values of their placeholders in that language from
 * the notice. A plain listener, as for the tasks: the notification is written in the transaction of the change, so a
 * rollback leaves neither; the delivery to open streams waits for the commit. The person who acted is not told, nor
 * one who switched these notifications off.
 */
@Component
@Profile("!migrate")
public class MsEntityNoticeListener {

    /** The kind of these notifications in the user's preferences. */
    static final String EVENT_TYPE = "entity_step";

    private final MsNotificationService notifications;
    private final MdUserTexts texts;

    public MsEntityNoticeListener(MsNotificationService notifications, MdUserTexts texts) {
        this.notifications = notifications;
        this.texts = texts;
    }

    @EventListener
    public void onNotice(EntityNotice notice) {
        for (long user : notice.recipients()) {
            if (user == notice.actorId()) continue;
            if (!notifications.isNotificationEnabled(user, EVENT_TYPE, "in_app")) continue;
            Map<String, String> params = notice.params().get(texts.language(user));
            if (params == null) params = notice.params().getOrDefault("ru", Map.of());
            notifications.sendInAppNotification(
                    user,
                    MsNotifyPref.TYPE_INFO,
                    texts.text(user, notice.titleKey(), params),
                    texts.text(user, notice.bodyKey(), params),
                    notice.link(),
                    notice.sourceCode());
        }
    }
}
