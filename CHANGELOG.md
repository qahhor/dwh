# Changelog

All notable changes to SmartupCMS will be documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and releases use [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Local, audited announcement authoring and lifecycle management.
- Read-only system status API and administration screen.
- S3-compatible object storage for AWS S3, Cloudflare R2, MinIO, and compatible
  providers.
- Encrypted database backup sidecar with sanitized local status reporting.
- Apache-2.0 community, governance, security, and contribution policies.

### Changed

- Unified the product as one SmartupCMS installation for one organization and
  many users.
- Renamed runtime applications to `server` and `web` and consolidated the
  production Compose topology.
- Made all standard runtime behavior local and disabled outbound telemetry by
  default.

### Removed

- Control Plane, fleet management, heartbeat, enrollment, and license gates.

[Unreleased]: https://github.com/qahhor/dwh/commits/main
