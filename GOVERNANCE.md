# SmartupCMS Governance

SmartupCMS is an Apache-2.0 open-source project stewarded by Smartup. The public
repository contains the complete product; Smartup's commercial offering is
hosting, operations, support, and SLA rather than a separate feature edition.

## Roles

- **Contributors** propose issues, documentation, code, tests, and reviews.
- **Maintainers** review contributions, protect release quality, moderate the
  community, and may merge changes.
- **Project stewards at Smartup** appoint maintainers and make final decisions
  on product direction, security embargoes, trademarks, and releases.

Maintainer status is earned through sustained, technically sound, and
constructive participation. A project steward may grant or revoke it after a
documented review of repository activity and community conduct.

## Decisions

Routine changes are decided in issues and pull requests. Maintainers seek
reasoned consensus, with evidence from requirements, tests, operations, and
user impact. When consensus is not practical, the responsible maintainer makes
the decision and records the rationale. Architecture changes require an ADR.
Project stewards resolve cross-cutting or unresolved decisions.

Security-sensitive discussions may remain private until a coordinated fix is
available. Commercial customer information and support cases are never used as
public decision records without explicit permission.

## Releases

Maintainers release from a green protected default branch using immutable
SemVer tags. A release must satisfy the repository CI, migration, security,
artifact, and clean-deployment gates documented in the release checklist.

## Contributions and licensing

All contributions require a Developer Certificate of Origin sign-off. No CLA
is required. Contributions accepted into the repository are licensed under
Apache-2.0; see [DCO](DCO), [CONTRIBUTING.md](CONTRIBUTING.md), and
[LICENSE](LICENSE).
