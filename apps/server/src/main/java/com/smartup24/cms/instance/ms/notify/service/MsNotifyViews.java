package com.smartup24.cms.instance.ms.notify.service;

import com.smartup24.cms.instance.ms.notify.api.AnnouncementView;
import com.smartup24.cms.instance.ms.notify.api.ManagedAnnouncementView;
import com.smartup24.cms.instance.ms.notify.api.NotificationPrefView;
import com.smartup24.cms.instance.ms.notify.api.NotificationView;
import com.smartup24.cms.instance.ms.notify.repository.MsAnnouncementRepository.AnnouncementRecord;
import com.smartup24.cms.instance.ms.notify.repository.MsAnnouncementRepository.ManagedAnnouncementRecord;
import com.smartup24.cms.instance.ms.notify.repository.MsNotificationPrefRepository.NotificationPrefRecord;
import com.smartup24.cms.instance.ms.notify.repository.MsNotificationRepository.NotificationRecord;

/** Repository rows to the DTOs of {@code ms.notify.api}: the rows stay inside the module (plan 10/10, item 3.2). */
final class MsNotifyViews {

    private MsNotifyViews() {}

    static ManagedAnnouncementView managed(ManagedAnnouncementRecord announcement) {
        return new ManagedAnnouncementView(
                announcement.id(),
                announcement.titleJson(),
                announcement.bodyJson(),
                announcement.bannerType(),
                announcement.state(),
                announcement.createdBy(),
                announcement.createdAt(),
                announcement.modifiedAt(),
                announcement.publishedAt(),
                announcement.archivedAt(),
                announcement.lockVersion());
    }

    static AnnouncementView announcement(AnnouncementRecord announcement) {
        return new AnnouncementView(
                announcement.id(),
                announcement.title(),
                announcement.body(),
                announcement.bannerType(),
                announcement.publishedAt());
    }

    static NotificationView notification(NotificationRecord notification) {
        return new NotificationView(
                notification.id(),
                notification.userId(),
                notification.type(),
                notification.title(),
                notification.body(),
                notification.formLink(),
                notification.sourceCode(),
                notification.isRead(),
                notification.createdAt());
    }

    static NotificationPrefView preference(NotificationPrefRecord preference) {
        return new NotificationPrefView(
                preference.userId(), preference.eventType(), preference.channel(), preference.isEnabled());
    }
}
