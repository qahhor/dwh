package com.greenwhite.dwh.cp.release;

import com.greenwhite.dwh.cp.audit.CpAuditRepository;
import com.greenwhite.dwh.cp.error.CpApiException;
import com.greenwhite.dwh.cp.pref.CpPref;
import com.greenwhite.dwh.cp.security.CpRequiresRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CpReleaseServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-01T12:00:00Z");
    private static final String SHA_A = "sha256:" + "a".repeat(64);
    private static final String SHA_B = "sha256:" + "b".repeat(64);

    private CpReleaseRepository repository;
    private CpAuditRepository auditRepository;
    private CpReleaseService service;

    @BeforeEach
    void setUp() {
        repository = mock(CpReleaseRepository.class);
        auditRepository = mock(CpAuditRepository.class);
        service = new CpReleaseService(
                repository,
                auditRepository,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void registersOnlyVerifiedImmutableDigestPinnedRelease() {
        VerifiedReleaseCommand command = validCommand();
        UUID releaseId = UUID.randomUUID();
        when(repository.findByVersion(command.version())).thenReturn(Optional.empty());
        when(repository.findByManifestDigest(command.manifestDigest())).thenReturn(Optional.empty());
        when(repository.insertReady(any(), any(), any())).thenReturn(releaseId);

        assertThat(service.registerVerified(command, "github-actions:release.yml@main"))
                .isEqualTo(releaseId);

        verify(repository).insertReady(command, "github-actions:release.yml@main", NOW);
        verify(auditRepository).record(
                "BUILD_IDENTITY",
                "github-actions:release.yml@main",
                "release.ready",
                "release",
                releaseId.toString());
    }

    @Test
    void rejectsInvalidCommitDigestMutableTagMismatchedReferenceAndMissingBundle() {
        assertInvalid(copy(validCommand(), "not-a-commit", null, null, null));
        assertInvalid(copy(validCommand(), null, "sha256:short", null, null));
        assertInvalid(withFirstComponent(validCommand(), new ReleaseComponent(
                "instance", "registry.invalid/dwh/instance:latest", SHA_A,
                SHA_A, SHA_A, "006", "006")));
        assertInvalid(withFirstComponent(validCommand(), new ReleaseComponent(
                "instance", "registry.invalid/dwh/instance@" + SHA_A, SHA_B,
                SHA_A, SHA_A, "006", "006")));
        assertInvalid(copy(validCommand(), null, null, "", null));
    }

    @Test
    void rejectsDuplicateOrMissingRequiredComponentsAndEmptyDeploymentModes() {
        List<ReleaseComponent> duplicates = new ArrayList<>(validCommand().components());
        duplicates.add(duplicates.getFirst());
        assertInvalid(copy(validCommand(), null, null, null, duplicates));

        assertInvalid(copy(validCommand(), null, null, null,
                validCommand().components().stream()
                        .filter(component -> !component.name().equals("proxy"))
                        .toList()));

        VerifiedReleaseCommand noModes = new VerifiedReleaseCommand(
                validCommand().version(),
                validCommand().sourceCommit(),
                validCommand().manifestDigest(),
                validCommand().manifestLocation(),
                validCommand().verificationBundleDigest(),
                validCommand().configSchemaVersion(),
                validCommand().minimumAgentVersion(),
                Set.of(),
                validCommand().components());
        assertInvalid(noModes);
    }

    @Test
    void exactRegistrationIsIdempotentButSameVersionWithDifferentMetadataCannotMutateReadyRelease() {
        VerifiedReleaseCommand command = validCommand();
        UUID releaseId = UUID.randomUUID();
        when(repository.findByVersion(command.version()))
                .thenReturn(Optional.of(release(releaseId, command, ReleaseStatus.READY)));

        assertThat(service.registerVerified(command, "build:one")).isEqualTo(releaseId);
        verify(repository, never()).insertReady(any(), any(), any());
        verify(auditRepository, never()).record(any(), any(), any(), any(), any());

        VerifiedReleaseCommand changed = copy(command, null, SHA_B, null, null);
        assertThatThrownBy(() -> service.registerVerified(changed, "build:two"))
                .isInstanceOfSatisfying(CpApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(error.errorCode()).isEqualTo("release_version_conflict");
                });
    }

    @Test
    void revokedReleaseCannotReturnToReady() {
        VerifiedReleaseCommand command = validCommand();
        when(repository.findByVersion(command.version()))
                .thenReturn(Optional.of(release(UUID.randomUUID(), command, ReleaseStatus.REVOKED)));

        assertThatThrownBy(() -> service.registerVerified(command, "build:retry"))
                .isInstanceOfSatisfying(CpApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(error.errorCode()).isEqualTo("release_revoked");
                });
        verify(repository, never()).insertReady(any(), any(), any());
    }

    @Test
    void revokeRequiresBoundedReasonAndAuditsOperator() {
        UUID releaseId = UUID.randomUUID();
        CpRelease ready = release(releaseId, validCommand(), ReleaseStatus.READY);
        when(repository.findById(releaseId)).thenReturn(Optional.of(ready));
        when(repository.revoke(releaseId, NOW)).thenReturn(1);

        CpRelease revoked = service.revoke(releaseId, "critical CVE", 17L);

        assertThat(revoked.status()).isEqualTo(ReleaseStatus.REVOKED);
        verify(auditRepository).record(
                "OPERATOR", "17", "release.revoked", "release", releaseId.toString(), "critical CVE");

        assertThatThrownBy(() -> service.revoke(releaseId, " ", 17L))
                .isInstanceOfSatisfying(CpApiException.class,
                        error -> assertThat(error.errorCode()).isEqualTo("release_revoke_reason_invalid"));
        assertThatThrownBy(() -> service.revoke(releaseId, "x".repeat(501), 17L))
                .isInstanceOfSatisfying(CpApiException.class,
                        error -> assertThat(error.errorCode()).isEqualTo("release_revoke_reason_invalid"));
    }

    @Test
    void requireReadyRejectsMissingDraftAndRevokedReleases() {
        UUID missing = UUID.randomUUID();
        when(repository.findById(missing)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.requireReady(missing))
                .isInstanceOfSatisfying(CpApiException.class,
                        error -> assertThat(error.errorCode()).isEqualTo("release_not_found"));

        UUID revokedId = UUID.randomUUID();
        when(repository.findById(revokedId)).thenReturn(Optional.of(
                release(revokedId, validCommand(), ReleaseStatus.REVOKED)));
        assertThatThrownBy(() -> service.requireReady(revokedId))
                .isInstanceOfSatisfying(CpApiException.class,
                        error -> assertThat(error.errorCode()).isEqualTo("release_not_ready"));
    }

    @Test
    void operatorHttpContractExposesOnlyListAndRevoke() {
        var methods = List.of(CpReleaseController.class.getDeclaredMethods());

        assertThat(methods.stream().filter(method -> method.isAnnotationPresent(GetMapping.class)))
                .singleElement()
                .satisfies(method -> {
                    assertThat(method.getName()).isEqualTo("list");
                    assertThat(method.getAnnotation(CpRequiresRole.class).value())
                            .containsExactlyInAnyOrder(CpPref.ROLE_ENGINEER, CpPref.ROLE_ADMIN);
                });
        assertThat(methods.stream().filter(method -> method.isAnnotationPresent(PostMapping.class)))
                .singleElement()
                .satisfies(method -> {
                    assertThat(method.getName()).isEqualTo("revoke");
                    assertThat(method.getAnnotation(PostMapping.class).value())
                            .containsExactly("/{releaseId}/revoke");
                    assertThat(method.getAnnotation(CpRequiresRole.class).value())
                            .containsExactly(CpPref.ROLE_ADMIN);
                });
        assertThat(methods)
                .noneMatch(method -> List.of(method.getParameterTypes())
                        .contains(VerifiedReleaseCommand.class));
    }

    private void assertInvalid(VerifiedReleaseCommand command) {
        assertThatThrownBy(() -> service.registerVerified(command, "build:test"))
                .isInstanceOfSatisfying(CpApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(error.errorCode()).isEqualTo("release_invalid");
                });
        verify(repository, never()).insertReady(any(), any(), any());
    }

    private static VerifiedReleaseCommand validCommand() {
        return new VerifiedReleaseCommand(
                "2026.9.1",
                "0123456789abcdef0123456789abcdef01234567",
                SHA_A,
                URI.create("https://artifacts.invalid/releases/2026.9.1/manifest.json"),
                SHA_B,
                "1",
                "1.0.0",
                Set.of(DeploymentMode.MANAGED_CLOUD, DeploymentMode.CUSTOMER_HOSTED),
                List.of(
                        component("instance"),
                        component("web"),
                        component("postgres"),
                        component("typesense"),
                        component("proxy")));
    }

    private static ReleaseComponent component(String name) {
        return new ReleaseComponent(
                name,
                "registry.invalid/dwh/" + name + "@" + SHA_A,
                SHA_A,
                SHA_B,
                SHA_A,
                "006",
                "006");
    }

    private static VerifiedReleaseCommand withFirstComponent(
            VerifiedReleaseCommand source,
            ReleaseComponent first) {
        List<ReleaseComponent> components = new ArrayList<>(source.components());
        components.set(0, first);
        return copy(source, null, null, null, components);
    }

    private static VerifiedReleaseCommand copy(
            VerifiedReleaseCommand source,
            String sourceCommit,
            String manifestDigest,
            String verificationBundleDigest,
            List<ReleaseComponent> components) {
        return new VerifiedReleaseCommand(
                source.version(),
                sourceCommit != null ? sourceCommit : source.sourceCommit(),
                manifestDigest != null ? manifestDigest : source.manifestDigest(),
                source.manifestLocation(),
                verificationBundleDigest != null ? verificationBundleDigest : source.verificationBundleDigest(),
                source.configSchemaVersion(),
                source.minimumAgentVersion(),
                source.deploymentModes(),
                components != null ? components : source.components());
    }

    private static CpRelease release(
            UUID id,
            VerifiedReleaseCommand command,
            ReleaseStatus status) {
        return new CpRelease(
                id,
                command.version(),
                command.sourceCommit(),
                command.manifestDigest(),
                command.manifestLocation(),
                command.verificationBundleDigest(),
                command.configSchemaVersion(),
                command.minimumAgentVersion(),
                command.deploymentModes(),
                status,
                command.components(),
                NOW);
    }
}
