package com.smartup24.cms.instance.ms.notify.service;

import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.common.query.TimePage;
import com.smartup24.cms.instance.ms.notify.api.AnnouncementView;
import com.smartup24.cms.instance.ms.notify.api.NotificationPrefUpdate;
import com.smartup24.cms.instance.ms.notify.api.NotificationPrefView;
import com.smartup24.cms.instance.ms.notify.api.NotificationView;
import com.smartup24.cms.instance.ms.notify.repository.MsAnnouncementRepository;
import com.smartup24.cms.instance.ms.notify.repository.MsNotificationPrefRepository;
import com.smartup24.cms.instance.ms.notify.repository.MsNotificationRepository;
import com.smartup24.cms.instance.ms.notify.repository.MsOutboxRepository;
import com.smartup24.cms.instance.ms.notify.sse.MsNotificationCreatedEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MsNotificationService {

    private final MsNotificationRepository notificationRepository;
    private final MsOutboxRepository outboxRepository;
    private final MsAnnouncementRepository announcementRepository;
    private final MsNotificationPrefRepository prefRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Autowired
    public MsNotificationService(
            MsNotificationRepository notificationRepository,
            MsOutboxRepository outboxRepository,
            MsAnnouncementRepository announcementRepository,
            @Autowired(required = false) MsNotificationPrefRepository prefRepository,
            ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
        this.notificationRepository = notificationRepository;
        this.outboxRepository = outboxRepository;
        this.announcementRepository = announcementRepository;
        this.prefRepository = prefRepository;
    }

    public MsNotificationService(
            MsNotificationRepository notificationRepository,
            MsOutboxRepository outboxRepository,
            MsAnnouncementRepository announcementRepository,
            ApplicationEventPublisher eventPublisher) {
        this(notificationRepository, outboxRepository, announcementRepository, null, eventPublisher);
    }

    @Transactional(readOnly = true)
    public List<NotificationPrefView> getUserPreferences(Long userId) {
        if (prefRepository == null) return List.of();
        return prefRepository.findByUserId(userId).stream()
                .map(MsNotifyViews::preference)
                .toList();
    }

    @Transactional
    public void updateUserPreferences(Long userId, List<NotificationPrefUpdate> updates) {
        if (prefRepository == null || updates == null) return;
        for (var pref : updates) {
            prefRepository.upsert(userId, pref.eventType(), pref.channel(), pref.isEnabled());
        }
    }

    @Transactional(readOnly = true)
    public boolean isNotificationEnabled(Long userId, String eventType, String channel) {
        if (prefRepository == null) return true;
        return prefRepository.isEnabled(userId, eventType, channel, true);
    }

    @Transactional
    public void sendInAppNotification(
            Long userId, String type, String title, String body, String formLink, String sourceCode) {
        var created = notificationRepository.create(userId, type, title, body, formLink, sourceCode);
        // Delivery to open SSE streams happens after commit (MsSsePublisher)
        eventPublisher.publishEvent(new MsNotificationCreatedEvent(userId, created));
    }

    @Transactional
    public void enqueueExternalNotification(
            String channel, String recipient, String templateCode, Map<String, Object> payload, UUID idempotencyKey) {
        outboxRepository.enqueue(channel, recipient, templateCode, payload, idempotencyKey);
    }

    @Transactional(readOnly = true)
    public KeysetPage<NotificationView> getUserNotifications(Long userId, TimePage page) {
        return page.page(
                notificationRepository.listUserNotifications(userId, page).stream()
                        .map(MsNotifyViews::notification)
                        .toList(),
                view -> new TimePage.Position(view.createdAt(), view.id()));
    }

    @Transactional(readOnly = true)
    public int getUnreadCount(Long userId) {
        return notificationRepository.getUnreadCount(userId);
    }

    @Transactional
    public void markAsRead(Long notificationId, Long userId) {
        notificationRepository.markAsRead(notificationId, userId);
    }

    @Transactional
    public void markAllAsRead(Long userId) {
        notificationRepository.markAllAsRead(userId);
    }

    @Transactional(readOnly = true)
    public List<AnnouncementView> getActiveAnnouncements(Long userId, String language) {
        return announcementRepository.getActiveUnreadAnnouncements(userId, language).stream()
                .map(MsNotifyViews::announcement)
                .toList();
    }

    @Transactional
    public void markAnnouncementAsRead(Long announcementId, Long userId) {
        announcementRepository.markAsRead(announcementId, userId);
    }

    @Transactional(readOnly = true)
    public boolean hasRecentNotification(Long userId, String sourceCode, Duration window) {
        return notificationRepository.hasRecentNotification(
                userId, sourceCode, Instant.now().minus(window));
    }
}
