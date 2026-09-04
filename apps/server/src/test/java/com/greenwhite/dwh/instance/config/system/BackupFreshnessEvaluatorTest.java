package com.greenwhite.dwh.instance.config.system;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class BackupFreshnessEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-09-04T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void classifiesSuccessfulBackupAgainstTheConfiguredMaximumAge() {
        BackupFreshnessEvaluator evaluator = new BackupFreshnessEvaluator(Duration.ofHours(24), CLOCK);

        BackupStatus current = evaluator.evaluate(new BackupStatus(
                "SUCCESS", NOW.minus(Duration.ofHours(23)), null));
        BackupStatus stale = evaluator.evaluate(new BackupStatus(
                "SUCCESS", NOW.minus(Duration.ofHours(25)), null));

        assertThat(current).isEqualTo(new BackupStatus(
                "SUCCESS", Instant.parse("2026-09-03T13:00:00Z"), null,
                "CURRENT", 82_800L, 86_400L));
        assertThat(stale).isEqualTo(new BackupStatus(
                "SUCCESS", Instant.parse("2026-09-03T11:00:00Z"), null,
                "STALE", 90_000L, 86_400L));
    }

    @Test
    void treatsTheExactMaximumAgeAsCurrent() {
        BackupFreshnessEvaluator evaluator = new BackupFreshnessEvaluator(Duration.ofHours(24), CLOCK);

        BackupStatus status = evaluator.evaluate(new BackupStatus(
                "SUCCESS", NOW.minus(Duration.ofHours(24)), null));

        assertThat(status.freshness()).isEqualTo("CURRENT");
    }

    @Test
    void reportsMissingPolicyWithoutPretendingTheBackupIsCurrent() {
        BackupFreshnessEvaluator evaluator = new BackupFreshnessEvaluator(Duration.ZERO, CLOCK);

        BackupStatus status = evaluator.evaluate(new BackupStatus(
                "SUCCESS", NOW.minus(Duration.ofHours(1)), null));

        assertThat(status).isEqualTo(new BackupStatus(
                "SUCCESS", Instant.parse("2026-09-04T11:00:00Z"), null,
                "NOT_CONFIGURED", 3_600L, null));
    }

    @Test
    void rejectsACompletionTimestampFromTheFuture() {
        BackupFreshnessEvaluator evaluator = new BackupFreshnessEvaluator(Duration.ofHours(24), CLOCK);

        BackupStatus status = evaluator.evaluate(new BackupStatus(
                "SUCCESS", NOW.plusSeconds(1), null));

        assertThat(status).isEqualTo(new BackupStatus(
                "SUCCESS", Instant.parse("2026-09-04T12:00:01Z"), null,
                "UNKNOWN", null, 86_400L));
    }

    @Test
    void doesNotApplySuccessfulBackupFreshnessToFailedAttempts() {
        BackupFreshnessEvaluator evaluator = new BackupFreshnessEvaluator(Duration.ofHours(24), CLOCK);

        BackupStatus status = evaluator.evaluate(new BackupStatus(
                "FAILED", NOW.minus(Duration.ofMinutes(5)), "UPLOAD_FAILED"));

        assertThat(status).isEqualTo(new BackupStatus(
                "FAILED", Instant.parse("2026-09-04T11:55:00Z"), "UPLOAD_FAILED",
                "NOT_APPLICABLE", null, 86_400L));
    }
}
