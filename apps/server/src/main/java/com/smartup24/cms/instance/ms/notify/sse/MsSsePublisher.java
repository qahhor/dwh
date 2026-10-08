package com.smartup24.cms.instance.ms.notify.sse;

import com.smartup24.cms.instance.common.cluster.ClusterNotices;
import com.smartup24.cms.instance.ms.notify.repository.MsNotificationRepository;
import com.smartup24.cms.instance.ms.notify.repository.MsNotificationRepository.NotificationRecord;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Delivers notifications to open SSE streams, on this node and on the other nodes of a cluster (FR-NOTIF-2,
 * ADR-0025, section 2.5).
 *
 * The local listener fires AFTER the transaction commits (AFTER_COMMIT): if the push
 * went out inside the transaction, the client could request the list and not see
 * the notification, or see one that was later rolled back.
 *
 * <p>A stream is open on one node only. Inside the transaction that creates a notification this node also sends the
 * other nodes a message on {@link #TOPIC} through {@link ClusterNotices} (PostgreSQL LISTEN/NOTIFY, delivered at the
 * commit, dropped with a rollback). The message names the user and the notification and nothing else; a node with a
 * stream of that user reads the row and pushes it only when the row belongs to that user.
 */
@Component
@Profile("!migrate")
public class MsSsePublisher {

    /** The cluster message topic; the message is {@code <user id> <notification id>}. */
    static final String TOPIC = "sse.notification";

    private static final Logger log = LoggerFactory.getLogger(MsSsePublisher.class);

    private final MsSseRegistry registry;
    private final @Nullable ClusterNotices cluster;
    private final @Nullable MsNotificationRepository notifications;

    @Autowired
    public MsSsePublisher(
            MsSseRegistry registry,
            ObjectProvider<ClusterNotices> cluster,
            ObjectProvider<MsNotificationRepository> notifications) {
        this(registry, cluster.getIfAvailable(), notifications.getIfAvailable());
    }

    /** A node of a cluster: it tells the others of its notifications and pushes theirs to its own streams. */
    public MsSsePublisher(
            MsSseRegistry registry,
            @Nullable ClusterNotices cluster,
            @Nullable MsNotificationRepository notifications) {
        this.registry = registry;
        this.cluster = cluster;
        this.notifications = notifications;
        if (cluster != null && notifications != null) {
            cluster.onMessage(TOPIC, this::onRemoteNotification);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onNotificationCreated(MsNotificationCreatedEvent event) {
        registry.send(event.userId(), "notification", payload(event.notification()));
    }

    /** Inside the creating transaction: the other nodes hear of the notification when it commits. */
    @EventListener
    public void tellOtherNodes(MsNotificationCreatedEvent event) {
        if (cluster != null && notifications != null) {
            cluster.send(TOPIC, event.userId() + " " + event.notification().id());
        }
    }

    /**
     * A notification created on another node. Only a user with a stream open here is looked up, and the row is pushed
     * only to the user it belongs to: the message itself is never trusted with the data.
     */
    void onRemoteNotification(String message) {
        String[] parts = message.split(" ");
        if (parts.length != 2 || notifications == null) {
            log.warn("sse_cluster_message_malformed message={}", message);
            return;
        }
        long userId;
        long notificationId;
        try {
            userId = Long.parseLong(parts[0]);
            notificationId = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            log.warn("sse_cluster_message_malformed message={}", message, e);
            return;
        }
        if (!registry.hasConnections(userId)) {
            return;
        }
        notifications
                .findById(notificationId)
                .filter(row -> row.userId() != null && row.userId() == userId)
                .ifPresent(row -> registry.send(userId, "notification", payload(row)));
    }

    static Map<String, Object> payload(NotificationRecord n) {
        return Map.of(
                "id", n.id(),
                "type", n.type(),
                "title", n.title(),
                "body", n.body(),
                "formLink", n.formLink() != null ? n.formLink() : "",
                "createdAt", n.createdAt().toString());
    }

    /** Keep-alive: proxies drop connections without traffic (usually after 60 s). */
    @Scheduled(fixedDelayString = "${smc.sse.heartbeat-ms:25000}")
    public void heartbeat() {
        registry.sendHeartbeat();
    }
}
