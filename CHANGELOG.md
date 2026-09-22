# Changelog

All notable changes to SmartupCMS will be documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and releases use [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Explicit primitive colour scales (`--color-<hue>-<step>`) behind the existing
  semantic design tokens, so a neighbouring step is available where one is
  needed for contrast.
- `npm run contrast:audit` gate, enforced in CI. It discovers every rule that
  sets both a background and a text colour, resolves both through the design
  tokens in each theme, and fails below the WCAG AA minimum.
- CI enforcement of the localization contract: the audit documented in the
  contribution guide now runs on every change, and the generated Russian
  fallback dictionary is regenerated and compared, so it can no longer drift
  from the canonical catalog unnoticed.
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
- Every remaining colour pair below WCAG AA, found by the new audit: danger and
  info text on their subtle backgrounds (4.29:1 and 4.00:1) now use dedicated
  `--danger-text` and `--info-text` inks; the dark tertiary text tier
  (3.17:1-3.61:1) moved one scale step lighter; and hard-coded light-theme
  literals in the sidebar count badge, the navigation row hover, and the
  embedded report error and fallback bars now read theme tokens, so those
  surfaces are no longer light-on-light in dark theme.

### Removed

- Control Plane, fleet management, heartbeat, enrollment, and license gates.

[Unreleased]: https://github.com/qahhor/dwh/commits/main
