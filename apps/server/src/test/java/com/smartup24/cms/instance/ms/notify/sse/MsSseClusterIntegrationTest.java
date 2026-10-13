package com.smartup24.cms.instance.ms.notify.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.common.security.SecurityContext.KauthPrincipal;
import com.smartup24.cms.instance.config.cache.CacheInvalidations;
import com.smartup24.cms.instance.kauth.repository.KauthSessionRepository;
import com.smartup24.cms.instance.md.service.MdPermissionService;
import com.smartup24.cms.instance.md.service.MdUserService;
import com.smartup24.cms.instance.ms.notify.repository.MsNotificationRepository;
import com.smartup24.cms.instance.ms.notify.service.MsNotificationService;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * FR-NOTIF-2, ADR-0025 section 2.5: a notification created on one node reaches a stream open on another node within two
 * seconds of the commit; a rolled-back one reaches nobody, and a node pushes a row only to the user it belongs to. The
 * second node is a registry and a publisher of its own with its own listening connection on the same database, as a
 * second instance of the application would be.
 */
class MsSseClusterIntegrationTest extends EmbeddedPostgresTest {

    private static final Duration WITHIN = Duration.ofSeconds(2);

    @Autowired
    private MsNotificationService notifications;

    @Autowired
    private MsNotificationRepository repository;

    @Autowired
    private CacheInvalidations invalidations;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private MsSseAccess access;

    @Autowired
    private KauthSessionRepository sessions;

    @Autowired
    private MdUserService users;

    @Autowired
    private MdPermissionService permissions;

    private CacheInvalidations secondNode;
    private RecordingRegistry secondStreams;
    private MsSsePublisher secondPublisher;
    private long user;
    private String source;

    @BeforeEach
    void startSecondNode() {
        secondNode = new CacheInvalidations(dataSource, jdbc);
        secondStreams = new RecordingRegistry();
        secondPublisher = new MsSsePublisher(secondStreams, secondNode, repository);
        secondNode.start();
        awaitTrue(() -> secondNode.isListening() && invalidations.isListening(), Duration.ofSeconds(10));
        user = jdbc.sql("select id from md_users where login = 'system'")
                .query(Long.class)
                .single();
        source = "sse-cluster-" + UUID.randomUUID();
    }

    @AfterEach
    void stopSecondNode() {
        secondNode.stop();
        jdbc.sql("delete from ms_notifications where source_code = :source")
                .param("source", source)
                .update();
    }

    @Test
    @DisplayName("FR-NOTIF-2: a notification created on one node reaches the user's stream on another")
    void committedNotificationReachesTheOtherNode() {
        secondStreams.online = user;

        transactions.executeWithoutResult(
                status -> notifications.sendInAppNotification(user, "info", "Title", "Body", "/tasks", source));

        awaitTrue(() -> !secondStreams.sent.isEmpty(), WITHIN);
        var pushed = secondStreams.sent.getFirst();
        assertThat(pushed.getKey()).isEqualTo(user);
        assertThat(pushed.getValue()).containsEntry("title", "Title").containsEntry("formLink", "/tasks");
    }

    @Test
    @DisplayName("FR-NOTIF-2: a rolled-back notification reaches no other node")
    void rolledBackNotificationTellsNobody() throws InterruptedException {
        secondStreams.online = user;

        transactions.executeWithoutResult(status -> {
            notifications.sendInAppNotification(user, "info", "Title", "Body", null, source);
            status.setRollbackOnly();
        });

        Thread.sleep(WITHIN.toMillis());
        assertThat(secondStreams.sent).isEmpty();
    }

    @Test
    @DisplayName("FR-NOTIF-2: a node without the user's stream reads nothing and pushes nothing")
    void nodeWithoutTheStreamStaysQuiet() throws InterruptedException {
        transactions.executeWithoutResult(
                status -> notifications.sendInAppNotification(user, "info", "Title", "Body", null, source));

        Thread.sleep(WITHIN.toMillis());
        assertThat(secondStreams.sent).isEmpty();
    }

    @Test
    @DisplayName("FR-NOTIF-2: a message naming another user does not push that user someone else's row")
    void rowIsPushedOnlyToItsOwner() {
        long stranger = user + 100_000;
        secondStreams.online = stranger;
        var row = transactions.execute(status -> repository.create(user, "info", "Private", "Body", null, source));

        secondPublisher.onRemoteNotification(stranger + " " + row.id());
        secondPublisher.onRemoteNotification("not a message");

        assertThat(secondStreams.sent).isEmpty();
    }

    @Test
    void theOtherNodeStopsSendingAfterSessionRevocationWithoutARevocationNotice() throws Exception {
        user = jdbc.sql("insert into md_users(name, login, email) values (:name, :name, :name) returning id")
                .param("name", source)
                .query(Long.class)
                .single();
        jdbc.sql("insert into md_user_permissions(user_id, form_code, action) values (:user, 'notify.inbox', 'view')")
                .param("user", user)
                .update();
        permissions.recalculateEffectivePermissions(user);
        var identity = users.getUserIdentity(user);
        var session = sessions.create(
                user,
                identity.authenticationVersion(),
                UUID.randomUUID().toString(),
                "127.0.0.1",
                "cluster-test",
                "web");
        var streams = new MsSseRegistry(60_000, 5, access);
        var publisher = new MsSsePublisher(streams, secondNode, repository);
        var mvc = MockMvcBuilders.standaloneSetup(new MsSseController(streams)).build();
        SecurityContext.setPrincipal(new KauthPrincipal(
                user,
                identity.login(),
                identity.email(),
                session.id(),
                false,
                permissions.getEffectivePermissions(user),
                permissions.getPermissionVersion(user),
                false,
                identity.authenticationVersion(),
                null));
        org.springframework.test.web.servlet.MvcResult response;
        try {
            response = mvc.perform(get("/api/v1/events")).andReturn();
        } finally {
            SecurityContext.clear();
        }
        transactions.executeWithoutResult(status ->
                notifications.sendInAppNotification(user, "info", "before-revoke", "visible-body", null, source));
        awaitTrue(
                () -> new String(
                                response.getResponse().getContentAsByteArray(), java.nio.charset.StandardCharsets.UTF_8)
                        .contains("before-revoke"),
                WITHIN);
        sessions.close(session.id());

        transactions.executeWithoutResult(status ->
                notifications.sendInAppNotification(user, "info", "after-revoke", "private-body", null, source));

        awaitTrue(() -> streams.openConnectionCount() == 0, WITHIN);
        assertThat(response.getResponse().getContentAsString()).doesNotContain("after-revoke", "private-body");
        publisher.heartbeat();
    }

    /** The streams of the second node: who is online there and what it pushed. */
    static final class RecordingRegistry extends MsSseRegistry {

        final List<Map.Entry<Long, Map<String, Object>>> sent = new CopyOnWriteArrayList<>();
        volatile Long online;

        RecordingRegistry() {
            super(60_000, 5, org.mockito.Mockito.mock(MsSseAccess.class));
        }

        @Override
        public boolean hasConnections(Long userId) {
            return userId.equals(online);
        }

        @Override
        @SuppressWarnings("unchecked")
        public void send(Long userId, String eventName, Object payload) {
            sent.add(Map.entry(userId, (Map<String, Object>) payload));
        }
    }

    private static void awaitTrue(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within " + timeout);
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted", interrupted);
            }
        }
    }
}
