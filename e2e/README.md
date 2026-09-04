# SmartupCMS browser E2E

The suite exercises the deployed SmartupCMS application through Chromium. It
complements Angular unit tests and the API live suites in `scripts/dev/`.

## Local run

Start the stack from the repository root and migrate the database:

```powershell
docker compose run --rm migrate
docker compose up -d --remove-orphans
.\scripts\dev\test-e2e.ps1
```

For repeated runs after dependencies are already installed:

```powershell
.\scripts\dev\test-e2e.ps1 -SkipInstall
```

Credentials are resolved from process environment first and the ignored root `.env`
second. `ADMIN_PASSWORD` is required and the login defaults to `admin`.
The base URL can be overridden with `INSTANCE_BASE_URL`. The launcher's readiness
checks use the same UI variable; a non-default management endpoint can be set with
`INSTANCE_HEALTH_URL`.

## Coverage

- Protected-route redirect, invalid login, admin navigation and logout;
- Project → task → comment vertical slice;
- User create/delete and file upload/delete through the visible UI;
- Local System status and announcement draft → publish → archive lifecycle;
- Keyboard focus, narrow viewport overflow and critical/serious axe checks on
  the local administration screens;
- Central translation editing, immediate repaint, Russian per-key fallback and
  persistence in a second authenticated browser session;
- browser console and uncaught page-error checks after authentication.

Tests use accessible roles and labels. Trace, video and HTML reports are disabled so
credentials and one-time tokens cannot be retained. Credentials are entered through a
redacted helper and cleared from the DOM before failure context can be captured. The
artifact-security probe exercises the production authentication and token-dismiss paths,
then intentionally fails with sentinel password/token values and scans reporter output
plus every generated artifact. Failure screenshots remain enabled.
