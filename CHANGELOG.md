# Changelog

All notable changes to SmartupCMS will be documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and releases use [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Explicit primitive colour scales (`--color-<hue>-<step>`) behind the existing
  semantic design tokens, so a neighbouring step is available where one is
  needed for contrast.
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

### Fixed

- Dark-theme text on solid accent fills no longer uses a hard-coded white.
  Primary buttons, active pagination and sidebar items, avatars, and tabs now
  read the per-theme `--on-primary` ink, raising the worst measured contrast
  from 2.14:1 to 9.07:1. Destructive buttons (3.76:1) and danger badges and
  alerts (4.29:1) were below WCAG AA in dark theme and now pass at 5.16:1 and
  5.84:1.

### Removed

- Control Plane, fleet management, heartbeat, enrollment, and license gates.

[Unreleased]: https://github.com/qahhor/dwh/commits/main
