# SmartupCMS browser E2E

The suite exercises the deployed Instance and Control Plane applications through
Chromium. It complements Angular unit tests and the API live suites in `scripts/dev/`.

## Local run

Start the full stack from the repository root and migrate both databases:

```powershell
docker compose run --rm migrate
docker compose run --rm migrate-cp
docker compose up -d --remove-orphans
.\scripts\dev\test-e2e.ps1
```

For repeated runs after dependencies are already installed:

```powershell
.\scripts\dev\test-e2e.ps1 -SkipInstall
```

Credentials are resolved from process environment first and the ignored root `.env`
second. Required keys are `ADMIN_PASSWORD` and `CP_ADMIN_PASSWORD`;
`CP_ADMIN_LOGIN` defaults to `cpadmin` and the Instance login defaults to `admin`.
Base URLs can be overridden with `INSTANCE_BASE_URL` and `CP_BASE_URL`.
The launcher's readiness checks use those same UI variables; non-default management
endpoints can be set with `INSTANCE_HEALTH_URL` and `CP_HEALTH_URL`.

## Coverage

- Instance protected-route redirect, invalid login, admin navigation and logout;
- Instance project → task → comment vertical slice;
- Control Plane invalid login, admin navigation and logout;
- Control Plane client → instance registration;
- Control Plane announcement → publish → archive lifecycle;
- browser console and uncaught page-error checks after authentication.

Tests use accessible roles and labels. Trace, video and HTML reports are disabled so
credentials and one-time tokens cannot be retained. Credentials are entered through a
redacted helper and cleared from the DOM before failure context can be captured. The
artifact-security probe exercises the production authentication and token-dismiss paths,
then intentionally fails with sentinel password/token values and scans reporter output
plus every generated artifact. Failure screenshots remain enabled, except for the
client/instance flow that briefly renders a one-time heartbeat token.
