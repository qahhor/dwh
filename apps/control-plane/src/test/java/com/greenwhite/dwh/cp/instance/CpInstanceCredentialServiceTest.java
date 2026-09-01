package com.greenwhite.dwh.cp.instance;

import com.greenwhite.dwh.cp.audit.CpAuditRepository;
import com.greenwhite.dwh.cp.error.CpApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CpInstanceCredentialServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    private static final String ENROLLMENT_HASH =
            "d3f9ce1515a870799f28b41da5243160d3e2b889cc4d7ac3507bc8bcef9694b8";
    private static final String CREDENTIAL_HASH =
            "169fe0b19d7947581c87df4323b779c31905007039d07179aeac0ca342f40ba1";
    private static final String ROTATED_CREDENTIAL_HASH =
            "d36af989267de568a895efd82e2364d82a45c6c6bd43ff1d6deaf4c3d131fdf3";

    private CpInstanceCredentialRepository repository;
    private CpAuditRepository auditRepository;
    private CpTokenGenerator tokenGenerator;
    private CpInstanceCredentialService service;

    @BeforeEach
    void setUp() {
        repository = mock(CpInstanceCredentialRepository.class);
        auditRepository = mock(CpAuditRepository.class);
        tokenGenerator = mock(CpTokenGenerator.class);
        service = new CpInstanceCredentialService(
                repository,
                auditRepository,
                tokenGenerator,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void issuesEnrollmentForFifteenMinutesAndPersistsOnlyItsHash() {
        String rawEnrollment = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        when(tokenGenerator.generate()).thenReturn(rawEnrollment);

        var issued = service.issueEnrollment(42L, 7L);

        assertThat(issued.instanceId()).isEqualTo(42L);
        assertThat(issued.enrollmentToken()).isEqualTo(rawEnrollment);
        assertThat(issued.expiresAt()).isEqualTo(Instant.parse("2026-09-01T00:15:00Z"));
        verify(repository).createEnrollment(
                42L,
                "0f007385b6f9d4b7eeb2748605afe1a984a0a3bfa3f014d09e2a784ce9e5cd1a",
                Instant.parse("2026-09-01T00:15:00Z"),
                7L);
    }

    @Test
    void exchangesEnrollmentOnlyOnceAndPersistsOnlyCredentialHash() {
        when(tokenGenerator.generate()).thenReturn("credential-raw");
        when(repository.exchange(ENROLLMENT_HASH, CREDENTIAL_HASH, NOW))
                .thenReturn(Optional.of(
                        new CpInstanceCredentialRepository.ExchangedCredential(91L, 42L)))
                .thenReturn(Optional.empty());

        assertThat(service.exchange("enroll-raw").credential()).isEqualTo("credential-raw");
        assertThatThrownBy(() -> service.exchange("enroll-raw"))
                .isInstanceOfSatisfying(CpApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(error.errorCode()).isEqualTo("instance_enrollment_invalid");
                });
    }

    @Test
    void rejectsExpiredOrUnknownEnrollmentWithoutLeakingItsState() {
        when(tokenGenerator.generate()).thenReturn("credential-raw");
        when(repository.exchange(ENROLLMENT_HASH, CREDENTIAL_HASH, NOW))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.exchange("enroll-raw"))
                .isInstanceOfSatisfying(CpApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(error.errorCode()).isEqualTo("instance_enrollment_invalid");
                });
    }

    @Test
    void authenticatesOnlyRepositoryAcceptedCredential() {
        var principal = new CpInstancePrincipal(42L, 12L, "client-a", 91L);
        when(repository.authenticate(CREDENTIAL_HASH, NOW)).thenReturn(Optional.of(principal));

        assertThat(service.authenticate("credential-raw")).contains(principal);
        assertThat(service.authenticate(" ")).isEmpty();
        verify(repository).authenticate(CREDENTIAL_HASH, NOW);
    }

    @Test
    void rotatesWithAClockBoundedTwentyFourHourOverlap() {
        var principal = new CpInstancePrincipal(42L, 12L, "client-a", 91L);
        when(tokenGenerator.generate()).thenReturn("rotate-credential");
        when(repository.rotate(
                42L,
                91L,
                ROTATED_CREDENTIAL_HASH,
                NOW,
                Instant.parse("2026-09-02T00:00:00Z")))
                .thenReturn(Optional.of(92L));

        var rotated = service.rotate(principal);

        assertThat(rotated.credential()).isEqualTo("rotate-credential");
        assertThat(rotated.previousValidUntil())
                .isEqualTo(Instant.parse("2026-09-02T00:00:00Z"));
    }

    @Test
    void returnsNotFoundWhenRevokeDoesNotMatchBothInstanceAndCredential() {
        when(repository.revoke(42L, 91L, NOW)).thenReturn(0);

        assertThatThrownBy(() -> service.revoke(42L, 91L, 7L))
                .isInstanceOfSatisfying(CpApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(error.errorCode()).isEqualTo("instance_credential_not_found");
                });
    }

    @Test
    void generatesExactlyThirtyTwoRandomBytesAsUnpaddedBase64Url() {
        CpTokenGenerator generator = new CpTokenGenerator();

        String first = generator.generate();
        String second = generator.generate();

        assertThat(first).matches("[A-Za-z0-9_-]{43}");
        assertThat(Base64.getUrlDecoder().decode(first)).hasSize(32);
        assertThat(second).isNotEqualTo(first);
    }
}
