# Security Policy

## Reporting a vulnerability

Do not open a public issue for a suspected vulnerability or include secrets,
personal data, exploit details, or customer data in public discussions.

Use [GitHub's private vulnerability reporting](https://github.com/qahhor/dwh/security/advisories/new)
to send the affected version, impact, reproduction steps, and any proposed
mitigation. The maintainers target acknowledgement within three business days
and an initial assessment within seven business days. These targets are not a
commercial SLA; managed-service customers follow their support agreement.

If private vulnerability reporting is unavailable, contact the repository
owner privately through their GitHub profile and disclose only enough
information to establish a secure channel.

## Supported versions

SmartupCMS is currently pre-1.0. Security fixes are made on the default branch
and on the latest published release when one exists. Older snapshots and
unreleased forks are not supported unless a separate support agreement says
otherwise.

## Security controls

- Server-side session and permission checks protect application APIs.
- Mutating browser requests use CSRF protection; API tokens are scoped.
- Audit records cover security-sensitive administrative actions.
- Production Compose exposes one web origin and keeps data services on an
  internal network.
- Database backups are streamed through `age` encryption before persistence.
- No telemetry or phone-home connection is enabled by default.

See [the security baseline](docs/adr/ADR-0008-security-baseline.md) and
[production deployment guide](docs/ops/deployment-guide.md) for operational
controls. These controls do not constitute a compliance certification.

## Disclosure

Please allow maintainers a reasonable remediation window before public
disclosure. We will coordinate attribution and disclosure timing with the
reporter and publish a security advisory when users need to take action.
