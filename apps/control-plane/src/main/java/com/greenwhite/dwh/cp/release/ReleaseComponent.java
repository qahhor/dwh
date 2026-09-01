package com.greenwhite.dwh.cp.release;

public record ReleaseComponent(
        String name,
        String imageReference,
        String imageDigest,
        String sbomDigest,
        String provenanceDigest,
        String minimumSchemaVersion,
        String maximumRollbackSchemaVersion) {
}
