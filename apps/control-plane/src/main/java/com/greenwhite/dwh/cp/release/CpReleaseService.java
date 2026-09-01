package com.greenwhite.dwh.cp.release;

import com.greenwhite.dwh.cp.audit.CpAuditRepository;
import com.greenwhite.dwh.cp.error.CpApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class CpReleaseService {

    private static final Pattern SEMVER = Pattern.compile(
            "^[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?$");
    private static final Pattern COMMIT = Pattern.compile("^[0-9a-f]{40}$");
    private static final Pattern DIGEST = Pattern.compile("^sha256:[0-9a-f]{64}$");
    private static final Pattern COMPONENT_NAME = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,63}$");
    private static final Set<String> REQUIRED_COMPONENTS =
            Set.of("instance", "web", "postgres", "typesense", "proxy");

    private final CpReleaseRepository repository;
    private final CpAuditRepository auditRepository;
    private final Clock clock;

    @Autowired
    public CpReleaseService(CpReleaseRepository repository,
                            CpAuditRepository auditRepository) {
        this(repository, auditRepository, Clock.systemUTC());
    }

    CpReleaseService(CpReleaseRepository repository,
                     CpAuditRepository auditRepository,
                     Clock clock) {
        this.repository = repository;
        this.auditRepository = auditRepository;
        this.clock = clock;
    }

    @Transactional
    public UUID registerVerified(VerifiedReleaseCommand command, String buildIdentity) {
        validate(command, buildIdentity);
        List.of("manifest:" + command.manifestDigest(), "version:" + command.version())
                .stream()
                .sorted()
                .forEach(repository::lockCatalogKey);

        var versionMatch = repository.findByVersion(command.version());
        if (versionMatch.isPresent()) {
            CpRelease existing = versionMatch.get();
            if (existing.status() == ReleaseStatus.REVOKED) {
                throw conflict("release_revoked", "Revoked release cannot return to READY");
            }
            if (existing.status() == ReleaseStatus.READY && sameMetadata(existing, command)) {
                return existing.id();
            }
            throw conflict("release_version_conflict",
                    "Release version already exists with different immutable metadata");
        }

        if (repository.findByManifestDigest(command.manifestDigest()).isPresent()) {
            throw conflict("release_manifest_conflict",
                    "Release manifest digest is already registered under another version");
        }

        Instant now = clock.instant();
        UUID releaseId = repository.insertReady(command, buildIdentity.trim(), now);
        auditRepository.record(
                "BUILD_IDENTITY",
                buildIdentity.trim(),
                "release.ready",
                "release",
                releaseId.toString());
        return releaseId;
    }

    @Transactional
    public CpRelease revoke(UUID releaseId, String reason, long actorUserId) {
        if (releaseId == null) {
            throw invalid("Release id is required");
        }
        String boundedReason = reason != null ? reason.trim() : "";
        if (boundedReason.isEmpty() || boundedReason.length() > 500) {
            throw new CpApiException(
                    HttpStatus.BAD_REQUEST,
                    "release_revoke_reason_invalid",
                    "Revoke reason must contain between 1 and 500 characters");
        }

        repository.lockCatalogKey("release-id:" + releaseId);
        CpRelease existing = repository.findById(releaseId)
                .orElseThrow(() -> new CpApiException(
                        HttpStatus.NOT_FOUND,
                        "release_not_found",
                        "Release not found"));
        if (existing.status() == ReleaseStatus.REVOKED) {
            return existing;
        }
        if (existing.status() != ReleaseStatus.READY) {
            throw conflict("release_not_ready", "Only a READY release can be revoked");
        }
        if (repository.revoke(releaseId, clock.instant()) != 1) {
            throw conflict("release_state_conflict", "Release state changed concurrently");
        }
        auditRepository.record(
                "OPERATOR",
                Long.toString(actorUserId),
                "release.revoked",
                "release",
                releaseId.toString(),
                boundedReason);
        return existing.withStatus(ReleaseStatus.REVOKED);
    }

    @Transactional(readOnly = true)
    public List<CpRelease> list() {
        return repository.list();
    }

    @Transactional
    public CpRelease requireReady(UUID releaseId) {
        CpRelease release = repository.findByIdForShare(releaseId)
                .orElseThrow(() -> new CpApiException(
                        HttpStatus.NOT_FOUND,
                        "release_not_found",
                        "Release not found"));
        if (release.status() != ReleaseStatus.READY) {
            throw conflict("release_not_ready", "Release is not READY");
        }
        return release;
    }

    private static void validate(VerifiedReleaseCommand command, String buildIdentity) {
        if (command == null) {
            throw invalid("Verified release command is required");
        }
        requireText(command.version(), 128, "version");
        if (!SEMVER.matcher(command.version()).matches()) {
            throw invalid("Release version must be semantic versioning");
        }
        if (command.sourceCommit() == null || !COMMIT.matcher(command.sourceCommit()).matches()) {
            throw invalid("Source commit must be a lowercase 40-hex SHA-1");
        }
        requireDigest(command.manifestDigest(), "manifestDigest");
        requireDigest(command.verificationBundleDigest(), "verificationBundleDigest");
        requireAbsoluteLocation(command.manifestLocation());
        requireText(command.configSchemaVersion(), 64, "configSchemaVersion");
        requireText(command.minimumAgentVersion(), 64, "minimumAgentVersion");
        requireText(buildIdentity, 200, "buildIdentity");
        if (command.deploymentModes() == null || command.deploymentModes().isEmpty()) {
            throw invalid("At least one deployment mode is required");
        }
        validateComponents(command.components());
    }

    private static void validateComponents(List<ReleaseComponent> components) {
        if (components == null || components.isEmpty() || components.size() > 100) {
            throw invalid("Release must contain between 1 and 100 components");
        }
        Map<String, ReleaseComponent> byName = new HashMap<>();
        for (ReleaseComponent component : components) {
            if (component == null) {
                throw invalid("Release component cannot be null");
            }
            requireText(component.name(), 64, "component.name");
            if (!COMPONENT_NAME.matcher(component.name()).matches()) {
                throw invalid("Component name has invalid format");
            }
            if (byName.put(component.name(), component) != null) {
                throw invalid("Duplicate release component: " + component.name());
            }
            requireText(component.imageReference(), 1024, "component.imageReference");
            requireDigest(component.imageDigest(), "component.imageDigest");
            requireDigest(component.sbomDigest(), "component.sbomDigest");
            requireDigest(component.provenanceDigest(), "component.provenanceDigest");
            optionalText(component.minimumSchemaVersion(), 64, "component.minimumSchemaVersion");
            optionalText(component.maximumRollbackSchemaVersion(), 64,
                    "component.maximumRollbackSchemaVersion");
            validateDigestPinnedReference(component);
        }
        if (!byName.keySet().containsAll(REQUIRED_COMPONENTS)) {
            throw invalid("Release is missing one or more required components");
        }
    }

    private static void validateDigestPinnedReference(ReleaseComponent component) {
        String reference = component.imageReference();
        String expectedSuffix = "@" + component.imageDigest();
        if (!reference.endsWith(expectedSuffix)
                || reference.indexOf('@') != reference.lastIndexOf('@')) {
            throw invalid("Image reference must be pinned to its image digest");
        }
        String repositoryReference = reference.substring(0, reference.length() - expectedSuffix.length());
        int lastSlash = repositoryReference.lastIndexOf('/');
        if (repositoryReference.indexOf(':', lastSlash + 1) >= 0) {
            throw invalid("Mutable image tags are not allowed");
        }
    }

    private static boolean sameMetadata(CpRelease existing, VerifiedReleaseCommand command) {
        return existing.version().equals(command.version())
                && existing.sourceCommit().equals(command.sourceCommit())
                && existing.manifestDigest().equals(command.manifestDigest())
                && existing.manifestLocation().equals(command.manifestLocation())
                && existing.verificationBundleDigest().equals(command.verificationBundleDigest())
                && existing.configSchemaVersion().equals(command.configSchemaVersion())
                && existing.minimumAgentVersion().equals(command.minimumAgentVersion())
                && existing.deploymentModes().equals(command.deploymentModes())
                && componentsByName(existing.components()).equals(componentsByName(command.components()));
    }

    private static Map<String, ReleaseComponent> componentsByName(List<ReleaseComponent> components) {
        return components.stream().collect(Collectors.toMap(ReleaseComponent::name, Function.identity()));
    }

    private static void requireAbsoluteLocation(URI location) {
        if (location == null || !location.isAbsolute() || location.getScheme() == null
                || location.toString().length() > 2048) {
            throw invalid("Manifest location must be an absolute URI up to 2048 characters");
        }
    }

    private static void requireDigest(String value, String field) {
        if (value == null || !DIGEST.matcher(value).matches()) {
            throw invalid(field + " must be a lowercase sha256 digest");
        }
    }

    private static void requireText(String value, int maxLength, String field) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw invalid(field + " must be present and bounded");
        }
    }

    private static void optionalText(String value, int maxLength, String field) {
        if (value != null && (value.isBlank() || value.length() > maxLength)) {
            throw invalid(field + " must be null or a bounded non-blank value");
        }
    }

    private static CpApiException invalid(String detail) {
        return new CpApiException(HttpStatus.BAD_REQUEST, "release_invalid", detail);
    }

    private static CpApiException conflict(String errorCode, String detail) {
        return new CpApiException(HttpStatus.CONFLICT, errorCode, detail);
    }
}
