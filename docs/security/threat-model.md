# SmartupCMS threat model and personal-data inventory

**Version:** 1.0

**Updated:** 2026-09-02

**Scope:** one SmartupCMS installation, one organization, many users

This is the repository baseline. The installation operator must add the real
domain names, network diagram, data owner, legal basis, retention decisions,
providers, incident contacts, and accepted residual risks before production.

## Assets and trust boundaries

Protected assets are session and API tokens, password hashes, provider and
webhook credentials, user identity data, tasks/comments/announcements, uploaded
objects, audit/security events, database backups, the age identity, and signed
release artifacts.

Trust boundaries are:

1. Browser to the single `web` HTTPS origin.
2. `web` reverse proxy to `server` on the private backend network.
3. `server` to PostgreSQL, Typesense, and local/S3-compatible object storage.
4. Optional `server` egress to explicitly configured notification providers and
   webhook destinations.
5. `backup` to PostgreSQL, local backup storage, and optional off-host R2/S3.
6. Operator access to the host, Docker daemon, secret files, release keys, and
   recovery material.

PostgreSQL, Typesense, the server port, and the management port are not public
in production Compose. The edge/reverse proxy, host OS, Docker daemon, DNS, and
configured external providers remain outside the application's trust boundary.

## Threats, current controls, and required operator controls

| Threat | Repository control | Installation requirement / residual risk |
|---|---|---|
| Credential theft and account takeover | Argon2id password hashing, hashed session/API tokens, session revocation, rate limits, forced bootstrap-password change | Protect TLS, secret files, mail/OTP providers, admin devices, and recovery channels; prove rotation and recovery |
| IDOR / privilege escalation | Server authentication plus `@RequiresPermission`, scoped task/file checks, fail-closed search for unsupported scope | Execute cross-role/direct-API negative tests against the release and review new endpoints |
| CSRF / XSS / clickjacking | Cookie requests require CSRF token; strict markdown URL handling; CSP, frame denial, referrer and permissions headers | Terminate HTTPS correctly and test headers at the external origin; inline styles remain allowed by the web CSP |
| SQL/command injection | Parameterized JDBC access and no user input passed to release shell commands | Static analysis and adversarial API tests remain required for every release |
| SSRF and uncontrolled egress | Webhooks disabled by default; exact host allow-list; private/special addresses rejected unless explicitly opted in; URL revalidated before dispatch; redirects disabled; connect/read timeouts; no default telemetry | Keep private-address opt-in false on managed/internet installations; enforce host-level egress controls and trusted DNS for enabled providers |
| Malicious file upload | 50 MB application limit, extension deny-list, permission-checked download, `Content-Disposition: attachment`, object quota | Content sniffing, malware scanning/quarantine, and an edge upload limit are not implemented; do not claim untrusted-file safety until added and tested |
| Secret/PII disclosure through API or logs | Webhook signing secret returned only at creation; webhook query/credentials redacted; structured audit; secret scan gate | Provider error text and support bundles must be reviewed; never attach dumps, object bytes, `.env`, or decrypted backups to public issues |
| Supply-chain compromise | Locked dependencies, pinned CI actions and base images, multi-arch builds, SBOM, provenance, keyless Cosign signing, digest verification | Protect repository permissions, branch/tag rules, and GitHub OIDC workflow; verify every deployed digest/signature/provenance |
| Data loss/corruption | Separate Flyway migration, mandatory pre-migration encrypted backup, checksums, restore and rollback scripts | Object bytes need a separate recovery source; execute target restore and object-consistency drills and keep age identity off-host |
| Availability / resource exhaustion | Request rate limits, file/company quotas, bounded webhook retries/timeouts, health endpoints, graceful shutdown | No bundled HA or alert delivery; size host and alerts for 100 concurrent users and 50 GB/month upload growth, then run a soak test |

## Personal-data inventory

| Data class | Examples and storage | Default lifecycle | Required decision before production |
|---|---|---|---|
| User identity/profile | name, login, email, phone, language, timezone, avatar and organization assignment in PostgreSQL; derived search documents in Typesense | Account data remains until an authorized anonymization/deletion workflow; search is rebuildable derived data | Controller/processor, purpose/legal basis, subject-request workflow, exact retention, search deletion verification |
| Authentication/security | password hash, session/token hashes, IP, user agent, login attempts, security events in PostgreSQL | Session/token expiry and revocation are application-controlled; no repository-wide retention rule for all security events was found | Retention, access roles, incident hold, export/deletion rules |
| Business content | tasks, comments, announcements, custom fields and metadata in PostgreSQL | Retained until authorized business deletion or installation policy | Classification, customer retention, deletion/hold rules |
| Uploaded files | original filename/MIME/hash/owner in PostgreSQL; bytes on local disk or S3-compatible storage | Retained until authorized deletion; deduplicated bytes are removed after the last owner record | Allowed content, malware policy, object versioning/lifecycle, erasure and recovery process |
| Audit records | changes, actor/session identifiers, IP/user agent and security-event details in PostgreSQL | Active audit partitions default to 12 months, then detach and remain archived; detaching is not deletion | Archived-partition owner, final disposition, legal/audit retention, access reviews |
| Backups | encrypted database copy locally and optionally off-host; object bytes require separate protection | Compose default is 14 days for database archives | Approved RPO/RTO, retention, off-host location, age-key custodians, tested object recovery |

SmartupCMS has no default telemetry or phone-home processor. A Smartup-managed
installation may use Cloudflare for edge security and R2 object storage; a
client-owned installation may configure another compatible provider. Those
providers and data locations must be recorded in the installation data map.

## Release review triggers

Review this model when an authentication method, public endpoint, permission,
outbound provider, file type, storage backend, data field, retention rule,
deployment topology, or privileged operator workflow changes. A release is
NO-GO until installation-specific gaps above have owners and evidence in the
[production launch checklist](../ops/production-launch-checklist.md).
