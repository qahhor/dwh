package com.smartup24.cms.instance.ms.notify;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.ms.notify.service.MsNotificationService;
import com.smartup24.cms.instance.ms.task.repository.MsTaskStatsRepository;
import com.smartup24.cms.instance.ms.task.worker.TaskDeadlineReminderWorker;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * FR-TASK-8: the deadline reminder is sent once per task and person even when several nodes scan at the same time.
 * Two runners on one database stand for two nodes of a cluster.
 */
class DeadlineReminderClusterIntegrationTest extends EmbeddedPostgresTest {

    private static final Duration WINDOW = Duration.ofHours(24);

    @Autowired
    private MsNotificationService notifications;

    @Autowired
    private MsTaskStatsRepository taskStats;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcClient jdbc;

    @Test
    @DisplayName("FR-TASK-8: the second of two concurrent senders waits for the first and sends nothing")
    void secondSenderWaitsAndSkips() throws Exception {
        long user = systemUser();
        String source = "task_deadline_probe_" + UUID.randomUUID();
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        CountDownLatch firstSent = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<Boolean> first = CompletableFuture.supplyAsync(
                    () -> transactions.execute(status -> {
                        boolean sent = send(user, source);
                        firstSent.countDown();
                        await(releaseFirst);
                        return sent;
                    }),
                    pool);
            assertThat(firstSent.await(10, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<Boolean> second =
                    CompletableFuture.supplyAsync(() -> transactions.execute(status -> send(user, source)), pool);

            Thread.sleep(500);
            assertThat(second)
                    .as("the second sender waits while the first is undecided")
                    .isNotDone();
            releaseFirst.countDown();

            assertThat(first.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(10, TimeUnit.SECONDS)).isFalse();
            assertThat(count(user, source)).isEqualTo(1);
        } finally {
            releaseFirst.countDown();
            pool.shutdownNow();
            delete(source);
        }
    }

    @Test
    @DisplayName("FR-TASK-8: two nodes scanning together send one reminder per task and person")
    void twoNodesSendOneReminder() throws Exception {
        long user = systemUser();
        long task = taskDueSoon(user);
        String source = "task_deadline_" + task;
        TaskDeadlineReminderWorker secondNode = new TaskDeadlineReminderWorker(taskStats, events, transactionManager);
        TaskDeadlineReminderWorker firstNode = new TaskDeadlineReminderWorker(taskStats, events, transactionManager);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 3; round++) {
                CyclicBarrier start = new CyclicBarrier(2);
                CompletableFuture<Void> a = CompletableFuture.runAsync(() -> scan(start, firstNode), pool);
                CompletableFuture<Void> b = CompletableFuture.runAsync(() -> scan(start, secondNode), pool);
                CompletableFuture.allOf(a, b).get(30, TimeUnit.SECONDS);
            }
            assertThat(count(user, source)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
            delete(source);
            jdbc.sql("delete from ms_task_members where task_id = :task")
                    .param("task", task)
                    .update();
            jdbc.sql("delete from ms_tasks where id = :task")
                    .param("task", task)
                    .update();
        }
    }

    private boolean send(long user, String source) {
        return notifications.sendInAppNotificationOnce(user, "warning", "title", "body", "/tasks", source, WINDOW);
    }

    private static void scan(CyclicBarrier start, TaskDeadlineReminderWorker node) {
        try {
            start.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        node.scanAndNotifyDeadlines();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private long systemUser() {
        return jdbc.sql("select id from md_users where login = 'system'")
                .query(Long.class)
                .single();
    }

    /** A task of {@code user} as its responsible person, due in two hours. */
    private long taskDueSoon(long user) {
        long task = jdbc.sql("""
                        insert into ms_tasks (title, priority, reporter_id, created_by, modified_by, end_time)
                        values ('TEST deadline reminder', 'medium', :user, :user, :user, now() + interval '2 hours')
                        returning id
                        """).param("user", user).query(Long.class).single();
        jdbc.sql("insert into ms_task_members (task_id, user_id, involve_kind) values (:task, :user, 'R')")
                .param("task", task)
                .param("user", user)
                .update();
        return task;
    }

    private int count(long user, String source) {
        return jdbc.sql("select count(*) from ms_notifications where user_id = :user and source_code = :source")
                .param("user", user)
                .param("source", source)
                .query(Integer.class)
                .single();
    }

    private void delete(String source) {
        jdbc.sql("delete from ms_notifications where source_code = :source")
                .param("source", source)
                .update();
    }
}
