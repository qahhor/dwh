package com.greenwhite.dwh.cp.instance;

import com.greenwhite.dwh.cp.audit.CpAuditRepository;
import com.greenwhite.dwh.cp.error.CpApiException;
import com.greenwhite.dwh.cp.security.CpPasswordHasher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Service
public class CpInstanceCredentialService {

    private static final Duration ENROLLMENT_TTL = Duration.ofMinutes(15);
    private static final Duration ROTATION_OVERLAP = Duration.ofHours(24);

    private final CpInstanceCredentialRepository repository;
    private final CpAuditRepository auditRepository;
    private final CpTokenGenerator tokenGenerator;
    private final Clock clock;

    @Autowired
    public CpInstanceCredentialService(CpInstanceCredentialRepository repository,
                                       CpAuditRepository auditRepository,
                                       CpTokenGenerator tokenGenerator) {
        this(repository, auditRepository, tokenGenerator, Clock.systemUTC());
    }

    CpInstanceCredentialService(CpInstanceCredentialRepository repository,
                                CpAuditRepository auditRepository,
                                CpTokenGenerator tokenGenerator,
                                Clock clock) {
        this.repository = repository;
        this.auditRepository = auditRepository;
        this.tokenGenerator = tokenGenerator;
        this.clock = clock;
    }

    @Transactional
    public IssuedEnrollment issueEnrollment(long instanceId, long actorUserId) {
        Instant expiresAt = clock.instant().plus(ENROLLMENT_TTL);
        String rawToken = tokenGenerator.generate();
        repository.createEnrollment(
                instanceId,
                CpPasswordHasher.sha256(rawToken),
                expiresAt,
                actorUserId);
        auditRepository.record(
                "OPERATOR",
                Long.toString(actorUserId),
                "instance.enrollment_issued",
                "instance",
                Long.toString(instanceId));
        return new IssuedEnrollment(instanceId, rawToken, expiresAt);
    }

    @Transactional
    public IssuedCredential exchange(String rawEnrollmentToken) {
        if (rawEnrollmentToken == null || rawEnrollmentToken.isBlank()) {
            throw invalidEnrollment();
        }

        Instant now = clock.instant();
        String rawCredential = tokenGenerator.generate();
        var exchanged = repository.exchange(
                        CpPasswordHasher.sha256(rawEnrollmentToken),
                        CpPasswordHasher.sha256(rawCredential),
                        now)
                .orElseThrow(CpInstanceCredentialService::invalidEnrollment);
        auditRepository.record(
                "INSTANCE",
                Long.toString(exchanged.instanceId()),
                "instance.credential_issued",
                "instance_credential",
                Long.toString(exchanged.credentialId()));
        return new IssuedCredential(exchanged.instanceId(), rawCredential, null);
    }

    @Transactional
    public Optional<CpInstancePrincipal> authenticate(String rawCredential) {
        if (rawCredential == null || rawCredential.isBlank()) {
            return Optional.empty();
        }
        return repository.authenticate(
                CpPasswordHasher.sha256(rawCredential),
                clock.instant());
    }

    @Transactional
    public IssuedCredential rotate(CpInstancePrincipal principal) {
        if (principal == null) {
            throw invalidCredential();
        }

        Instant now = clock.instant();
        Instant previousValidUntil = now.plus(ROTATION_OVERLAP);
        String rawCredential = tokenGenerator.generate();
        repository.rotate(
                        principal.instanceId(),
                        principal.credentialId(),
                        CpPasswordHasher.sha256(rawCredential),
                        now,
                        previousValidUntil)
                .orElseThrow(CpInstanceCredentialService::invalidCredential);
        auditRepository.record(
                "INSTANCE",
                Long.toString(principal.instanceId()),
                "instance.credential_rotated",
                "instance_credential",
                Long.toString(principal.credentialId()));
        return new IssuedCredential(
                principal.instanceId(),
                rawCredential,
                previousValidUntil);
    }

    @Transactional
    public void revoke(long instanceId, long credentialId, long actorUserId) {
        int affected = repository.revoke(instanceId, credentialId, clock.instant());
        if (affected != 1) {
            throw new CpApiException(
                    HttpStatus.NOT_FOUND,
                    "instance_credential_not_found",
                    "Instance credential was not found");
        }
        auditRepository.record(
                "OPERATOR",
                Long.toString(actorUserId),
                "instance.credential_revoked",
                "instance_credential",
                Long.toString(credentialId));
    }

    private static CpApiException invalidEnrollment() {
        return new CpApiException(
                HttpStatus.UNAUTHORIZED,
                "instance_enrollment_invalid",
                "Enrollment token is invalid or expired");
    }

    private static CpApiException invalidCredential() {
        return new CpApiException(
                HttpStatus.UNAUTHORIZED,
                "instance_credential_invalid",
                "Instance credential is invalid, expired or revoked");
    }

    public record IssuedEnrollment(
            long instanceId,
            String enrollmentToken,
            Instant expiresAt) {
    }

    public record IssuedCredential(
            long instanceId,
            String credential,
            Instant previousValidUntil) {
    }
}
