import { spawnSync } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { existsSync, readdirSync, readFileSync, rmSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const e2eDirectory = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const artifactsDirectory = resolve(e2eDirectory, 'artifact-security-results');
const playwrightCli = resolve(e2eDirectory, 'node_modules', '@playwright', 'test', 'cli.js');
const sentinels = [
  `e2e-password-sentinel-${randomBytes(24).toString('hex')}`,
  `e2e-token-sentinel-${randomBytes(24).toString('hex')}`,
];
const unexpectedReportDirectories = [
  resolve(e2eDirectory, 'playwright-report'),
  resolve(e2eDirectory, 'blob-report'),
];

function artifactFiles(directory) {
  if (!existsSync(directory)) return [];
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const path = resolve(directory, entry.name);
    return entry.isDirectory() ? artifactFiles(path) : [path];
  });
}

rmSync(artifactsDirectory, { recursive: true, force: true });

try {
  const probe = spawnSync(
    process.execPath,
    [playwrightCli, 'test', '--config=playwright.artifact-security.config.ts'],
    {
      cwd: e2eDirectory,
      encoding: 'utf8',
      env: {
        ...process.env,
        ADMIN_PASSWORD: sentinels[0],
        CP_ADMIN_PASSWORD: sentinels[0],
        E2E_ARTIFACT_TOKEN_SENTINEL: sentinels[1],
      },
      maxBuffer: 10 * 1024 * 1024,
    },
  );

  if (probe.error) throw probe.error;
  if (probe.status !== 1) {
    throw new Error(`Artifact security probe should fail intentionally, received exit code ${probe.status}`);
  }

  const output = `${probe.stdout ?? ''}\n${probe.stderr ?? ''}`;
  if (!output.includes('ARTIFACT_SECURITY_EXPECTED_FAILURE')) {
    const sanitizedOutput = sentinels.reduce(
      (sanitized, sentinel) => sanitized.replaceAll(sentinel, '[REDACTED]'),
      output,
    );
    process.stderr.write(sanitizedOutput);
    throw new Error('Artifact security probe did not reach its intentional failure');
  }

  const generatedFiles = artifactFiles(artifactsDirectory);
  const leakedInOutput = sentinels.some((sentinel) => output.includes(sentinel));
  const leakedFiles = generatedFiles.filter((path) => {
    const contents = readFileSync(path);
    return sentinels.some((sentinel) => contents.includes(Buffer.from(sentinel)));
  });
  const unexpectedArtifacts = generatedFiles.filter((path) => (
    !/\.(?:md|png)$/u.test(path) && !path.endsWith('.last-run.json')
  ));
  const unexpectedReports = unexpectedReportDirectories.filter((path) => existsSync(path));

  if (leakedInOutput || leakedFiles.length > 0) {
    const locations = [
      ...(leakedInOutput ? ['reporter output'] : []),
      ...leakedFiles.map((path) => path.slice(e2eDirectory.length + 1)),
    ];
    throw new Error(`Sentinel secret leaked into: ${locations.join(', ')}`);
  }
  if (unexpectedArtifacts.length > 0 || unexpectedReports.length > 0) {
    const paths = [...unexpectedArtifacts, ...unexpectedReports]
      .map((path) => path.slice(e2eDirectory.length + 1));
    throw new Error(`Playwright produced artifacts outside the approved allowlist: ${paths.join(', ')}`);
  }

  process.stdout.write('Playwright failure output and artifacts contain no sentinel secrets.\n');
} finally {
  rmSync(artifactsDirectory, { recursive: true, force: true });
}
