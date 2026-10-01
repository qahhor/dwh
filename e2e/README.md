# SmartupCMS browser E2E

The suite exercises the deployed SmartupCMS application through Chromium. It
complements Angular unit tests and the API live suites in `scripts/dev/`.

## Local run

Start the stack from the repository root with the mail stub and migrate the
database (on Linux and macOS separate the files with `:` instead of `;`):

```powershell
$env:COMPOSE_FILE = 'docker-compose.yml;scripts/dev/e2e-mail.compose.yml'
docker compose run --rm migrate
docker compose up -d --remove-orphans --wait
.\scripts\dev\test-e2e.ps1
```

`scripts/dev/e2e-mail.compose.yml` adds Mailpit and points the server's SMTP
settings at it, with delivery enforced as in production. `mail-delivery.spec.ts`
reads the mailed channel code, login code and reset link through Mailpit's HTTP
API (`MAILPIT_URL`, default `http://localhost:8025`; host port
`MAILPIT_HTTP_PORT`). The second web origin's host port is `E2E_WEB_PORT`
(default 4201). `-CheckReadiness` ends the run with
`scripts/dev/test-readiness-dependency.ps1`: it stops postgres, expects the
readiness probe to answer 503 within 30 s and 200 after postgres starts again.

For repeated runs after dependencies are already installed:

```powershell
.\scripts\dev\test-e2e.ps1 -SkipInstall
```

Credentials are resolved from process environment first and the ignored root `.env`
second. `ADMIN_PASSWORD` is required and the login defaults to `admin`.
The base URL can be overridden with `INSTANCE_BASE_URL`. The launcher's readiness
checks use the same UI variable; a non-default management endpoint can be set with
`INSTANCE_HEALTH_URL`.

## Accessibility gate

`npm run test:a11y` checks the screens built on the shared table, tree and
server table (languages, organizational structure, both audit lists, a
user's division assignments, the task list with a lookup open) against
WCAG 2.1 A and AA with axe, in light and dark themes. It needs no stack: it
serves the production build from `apps/web/dist/web/browser` (run
`npm run build` in `apps/web` first) with the API mocked by
`support/a11y-fixtures.mjs`, so it runs in the CI frontend job. Set
`PLAYWRIGHT_CHROMIUM_EXECUTABLE` to use a preinstalled Chromium.

## Coverage

- Protected-route redirect, invalid login, admin navigation and logout;
- Project → task → comment vertical slice;
- User create/delete and file upload/delete through the visible UI;
- Local System status and announcement draft → publish → archive lifecycle;
- Keyboard focus, narrow viewport overflow and critical/serious axe checks on
  the local administration screens;
- Central translation editing, immediate repaint, Russian per-key fallback and
  persistence in a second authenticated browser session;
- Email channel confirmed by a mailed code, two-factor sign-in with a mailed
  one-time code and password reset by a mailed link, through the SMTP stub;
- browser console and uncaught page-error checks after authentication.

Tests use accessible roles and labels. Trace, video and HTML reports are disabled so
credentials and one-time tokens cannot be retained. Credentials are entered through a
redacted helper and cleared from the DOM before failure context can be captured. The
artifact-security probe exercises the production authentication and token-dismiss paths,
then intentionally fails with sentinel password/token values and scans reporter output
plus every generated artifact. Failure screenshots remain enabled.
