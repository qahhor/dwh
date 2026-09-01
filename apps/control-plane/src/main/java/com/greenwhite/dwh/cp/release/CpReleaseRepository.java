package com.greenwhite.dwh.cp.release;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.net.URI;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class CpReleaseRepository {

    private static final long CATALOG_LOCK_SEED = 0x4457485F52454C4CL;

    private final JdbcClient jdbc;

    public CpReleaseRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void lockCatalogKey(String key) {
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:key, :seed))")
                .param("key", key)
                .param("seed", CATALOG_LOCK_SEED)
                .query((rs, rowNum) -> Boolean.TRUE)
                .single();
    }

    public UUID insertReady(VerifiedReleaseCommand command,
                            String buildIdentity,
                            Instant now) {
        UUID releaseId = jdbc.sql("""
                        insert into cp_releases(
                            version, source_commit, manifest_digest, manifest_location,
                            verification_bundle_digest, config_schema_version,
                            minimum_agent_version, deployment_modes, status,
                            created_by_identity, created_at, ready_at)
                        values (
                            :version, :sourceCommit, :manifestDigest, :manifestLocation,
                            :verificationBundleDigest, :configSchemaVersion,
                            :minimumAgentVersion, cast(:deploymentModes as text[]), 'READY',
                            :buildIdentity, :createdAt, :readyAt)
                        returning id
                        """)
                .param("version", command.version())
                .param("sourceCommit", command.sourceCommit())
                .param("manifestDigest", command.manifestDigest())
                .param("manifestLocation", command.manifestLocation().toString())
                .param("verificationBundleDigest", command.verificationBundleDigest())
                .param("configSchemaVersion", command.configSchemaVersion())
                .param("minimumAgentVersion", command.minimumAgentVersion())
                .param("deploymentModes", postgresArray(command.deploymentModes()))
                .param("buildIdentity", buildIdentity)
                .param("createdAt", dbTime(now))
                .param("readyAt", dbTime(now))
                .query(UUID.class)
                .single();

        for (ReleaseComponent component : command.components()) {
            jdbc.sql("""
                            insert into cp_release_components(
                                release_id, component_name, image_reference, image_digest,
                                sbom_digest, provenance_digest, minimum_schema_version,
                                maximum_rollback_schema_version)
                            values (
                                :releaseId, :componentName, :imageReference, :imageDigest,
                                :sbomDigest, :provenanceDigest, :minimumSchemaVersion,
                                :maximumRollbackSchemaVersion)
                            """)
                    .param("releaseId", releaseId)
                    .param("componentName", component.name())
                    .param("imageReference", component.imageReference())
                    .param("imageDigest", component.imageDigest())
                    .param("sbomDigest", component.sbomDigest())
                    .param("provenanceDigest", component.provenanceDigest())
                    .param("minimumSchemaVersion", component.minimumSchemaVersion())
                    .param("maximumRollbackSchemaVersion", component.maximumRollbackSchemaVersion())
                    .update();
        }
        return releaseId;
    }

    public Optional<CpRelease> findByVersion(String version) {
        return jdbc.sql(RELEASE_SELECT + " where version = :version")
                .param("version", version)
                .query(CpReleaseRepository::mapRow)
                .optional()
                .map(this::assemble);
    }

    public Optional<CpRelease> findByManifestDigest(String manifestDigest) {
        return jdbc.sql(RELEASE_SELECT + " where manifest_digest = :manifestDigest")
                .param("manifestDigest", manifestDigest)
                .query(CpReleaseRepository::mapRow)
                .optional()
                .map(this::assemble);
    }

    public Optional<CpRelease> findById(UUID releaseId) {
        return jdbc.sql(RELEASE_SELECT + " where id = :releaseId")
                .param("releaseId", releaseId)
                .query(CpReleaseRepository::mapRow)
                .optional()
                .map(this::assemble);
    }

    public Optional<CpRelease> findByIdForShare(UUID releaseId) {
        return jdbc.sql(RELEASE_SELECT + " where id = :releaseId for share")
                .param("releaseId", releaseId)
                .query(CpReleaseRepository::mapRow)
                .optional()
                .map(this::assemble);
    }

    public CpRelease requireById(UUID releaseId) {
        return findById(releaseId)
                .orElseThrow(() -> new IllegalStateException("Release row not found: " + releaseId));
    }

    public List<CpRelease> list() {
        List<ReleaseRow> rows = jdbc.sql(RELEASE_SELECT + " order by created_at desc, version desc")
                .query(CpReleaseRepository::mapRow)
                .list();
        if (rows.isEmpty()) {
            return List.of();
        }

        Map<UUID, List<ReleaseComponent>> componentsByRelease = new LinkedHashMap<>();
        jdbc.sql(COMPONENT_SELECT + " order by release_id, component_name")
                .query((rs, rowNum) -> new ComponentRow(
                        rs.getObject("release_id", UUID.class),
                        mapComponent(rs)))
                .list()
                .forEach(row -> componentsByRelease
                        .computeIfAbsent(row.releaseId(), ignored -> new ArrayList<>())
                        .add(row.component()));

        return rows.stream()
                .map(row -> assemble(row, componentsByRelease.getOrDefault(row.id(), List.of())))
                .toList();
    }

    public int revoke(UUID releaseId, Instant revokedAt) {
        return jdbc.sql("""
                        update cp_releases
                        set status = 'REVOKED', revoked_at = :revokedAt
                        where id = :releaseId and status = 'READY'
                        """)
                .param("releaseId", releaseId)
                .param("revokedAt", dbTime(revokedAt))
                .update();
    }

    private CpRelease assemble(ReleaseRow row) {
        return assemble(row, components(row.id()));
    }

    private static CpRelease assemble(ReleaseRow row, List<ReleaseComponent> components) {
        return new CpRelease(
                row.id(),
                row.version(),
                row.sourceCommit(),
                row.manifestDigest(),
                row.manifestLocation(),
                row.verificationBundleDigest(),
                row.configSchemaVersion(),
                row.minimumAgentVersion(),
                row.deploymentModes(),
                row.status(),
                components,
                row.createdAt());
    }

    private List<ReleaseComponent> components(UUID releaseId) {
        return jdbc.sql(COMPONENT_SELECT + " where release_id = :releaseId order by component_name")
                .param("releaseId", releaseId)
                .query((rs, rowNum) -> mapComponent(rs))
                .list();
    }

    private static ReleaseRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ReleaseRow(
                rs.getObject("id", UUID.class),
                rs.getString("version"),
                rs.getString("source_commit"),
                rs.getString("manifest_digest"),
                URI.create(rs.getString("manifest_location")),
                rs.getString("verification_bundle_digest"),
                rs.getString("config_schema_version"),
                rs.getString("minimum_agent_version"),
                deploymentModes(rs.getArray("deployment_modes")),
                ReleaseStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant());
    }

    private static Set<DeploymentMode> deploymentModes(Array array) throws SQLException {
        String[] values = (String[]) array.getArray();
        EnumSet<DeploymentMode> modes = EnumSet.noneOf(DeploymentMode.class);
        for (String value : values) {
            modes.add(DeploymentMode.valueOf(value));
        }
        return Set.copyOf(modes);
    }

    private static ReleaseComponent mapComponent(ResultSet rs) throws SQLException {
        return new ReleaseComponent(
                rs.getString("component_name"),
                rs.getString("image_reference"),
                rs.getString("image_digest"),
                rs.getString("sbom_digest"),
                rs.getString("provenance_digest"),
                rs.getString("minimum_schema_version"),
                rs.getString("maximum_rollback_schema_version"));
    }

    private static String postgresArray(Set<DeploymentMode> modes) {
        return modes.stream()
                .map(DeploymentMode::name)
                .sorted(Comparator.naturalOrder())
                .collect(java.util.stream.Collectors.joining(",", "{", "}"));
    }

    private static OffsetDateTime dbTime(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static final String RELEASE_SELECT = """
            select id, version, source_commit, manifest_digest, manifest_location,
                   verification_bundle_digest, config_schema_version,
                   minimum_agent_version, deployment_modes, status, created_at
            from cp_releases
            """;

    private static final String COMPONENT_SELECT = """
            select release_id, component_name, image_reference, image_digest,
                   sbom_digest, provenance_digest, minimum_schema_version,
                   maximum_rollback_schema_version
            from cp_release_components
            """;

    private record ReleaseRow(
            UUID id,
            String version,
            String sourceCommit,
            String manifestDigest,
            URI manifestLocation,
            String verificationBundleDigest,
            String configSchemaVersion,
            String minimumAgentVersion,
            Set<DeploymentMode> deploymentModes,
            ReleaseStatus status,
            Instant createdAt) {
    }

    private record ComponentRow(UUID releaseId, ReleaseComponent component) {
    }
}
