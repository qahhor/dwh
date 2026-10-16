import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { copyFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const root = fileURLToPath(new URL('../..', import.meta.url));
const bash = process.platform === 'win32' ? 'C:/Program Files/Git/bin/bash.exe' : 'bash';
const passwordNames = ['POSTGRES_PASSWORD', 'MIGRATE_DB_PASSWORD', 'DB_PASSWORD', 'BACKUP_DB_PASSWORD'];
const secret = () => randomBytes(32).toString('hex');
const portable = value => value.replaceAll('\\', '/');

function fixture(t) {
  const local = path.join(root, '.local');
  mkdirSync(local, { recursive: true });
  const directory = mkdtempSync(path.join(local, 'credential-test-'));
  t.after(() => {
    assert.ok(path.resolve(directory).startsWith(path.resolve(local) + path.sep), 'cleanup stays inside the test directory');
    rmSync(directory, { recursive: true, force: true });
  });
  const bin = path.join(directory, 'bin');
  mkdirSync(bin);
  const env = { ...process.env };
  for (const name of Object.keys(env)) {
    if (/^(POSTGRES|MIGRATE_DB|APP_DB|BACKUP_DB|DB|EXPECTED)_|^PG/.test(name)) delete env[name];
  }
  const pathKey = Object.keys(env).find(name => name.toLowerCase() === 'path') ?? 'PATH';
  env[pathKey] = bin + path.delimiter + env[pathKey];
  env.MSYS_NO_PATHCONV = '1';
  const marker = path.join(directory, 'sql-ran');
  env.PSQL_MARKER = portable(marker);
  const values = passwordNames.map(() => secret());
  passwordNames.forEach((name, index) => { env[name] = values[index]; });
  Object.assign(env, {
    POSTGRES_USER: 'postgres', MIGRATE_DB_USER: 'smartupcms_migrator',
    APP_DB_USER: 'smartupcms', BACKUP_DB_USER: 'smartupcms_backup',
    APP_DB_PASSWORD: env.DB_PASSWORD, WAREHOUSE_DB_NAME: '',
    EXPECTED_MIGRATE: values[1], EXPECTED_APP: values[2], EXPECTED_BACKUP: values[3],
  });
  for (const [index, name] of ['PGPASSWORD_FILE', 'MIGRATE_DB_PASSWORD_FILE', 'APP_DB_PASSWORD_FILE', 'BACKUP_DB_PASSWORD_FILE'].entries()) {
    env[name] = portable(path.join(directory, `secret-${index}`));
    writeFileSync(env[name], values[index], { mode: 0o600 });
  }
  writeFileSync(path.join(bin, 'psql'), `#!/bin/sh
touch "$PSQL_MARKER"
for arg in "$@"; do
  case "$arg" in
    --set=migrate_password=*) [ "\${arg#--set=migrate_password=}" = "$EXPECTED_MIGRATE" ] || exit 71 ;;
    --set=app_password=*) [ "\${arg#--set=app_password=}" = "$EXPECTED_APP" ] || exit 72 ;;
    --set=backup_password=*) [ "\${arg#--set=backup_password=}" = "$EXPECTED_BACKUP" ] || exit 73 ;;
  esac
done
cat >/dev/null
`, { mode: 0o700 });
  return { directory, bin, env, marker, values };
}

function execute(command, args, env, cwd = root) {
  const result = spawnSync(command, args, { cwd, env, encoding: 'utf8', timeout: 30000 });
  assert.equal(result.error?.code, undefined, 'test command must execute');
  return { ...result, output: `${result.stdout ?? ''}${result.stderr ?? ''}` };
}

function noLeaks(output, values) {
  assert.ok(values.every(value => !value || !output.includes(value)), 'credential values must not appear in output');
}

for (const runtime of ['bash', 'powershell']) {
  test(`${runtime} generator refuses an unusable backup key instead of publishing placeholder configuration`, t => {
    const f = fixture(t);
    writeFileSync(path.join(f.bin, 'age-keygen'), '#!/bin/sh\nexit 7\n', { mode: 0o700 });
    writeFileSync(path.join(f.bin, 'age-keygen.cmd'), '@exit /b 7\r\n');
    const envFile = path.join(f.directory, 'rejected.env');
    const secretsDir = path.join(f.directory, 'secrets');
    const identity = path.join(f.directory, 'identity');
    const env = { ...f.env, ADMIN_PASSWORD: secret(), SMC_PUBLIC_URL: 'https://fixture.invalid',
      SECRETS_DIR: portable(secretsDir), AGE_IDENTITY_FILE: portable(identity) };
    const result = runtime === 'bash'
      ? execute(bash, ['scripts/prod/init-production-env.sh', portable(envFile)], env)
      : execute('pwsh', ['-NoProfile', '-File', 'scripts/prod/init-production-env.ps1', '-EnvFile', envFile,
        '-SecretsDirectory', secretsDir, '-AgeIdentityFile', identity, '-PublicUrl', 'https://fixture.invalid'], env);
    assert.notEqual(result.status, 0);
    assert.equal(existsSync(envFile), false);
    assert.ok(!result.output.includes('Generated placeholder'));
  });
  test(`${runtime} generator creates four independent matching secret files at the requested location`, t => {
    const f = fixture(t);
    writeFileSync(path.join(f.bin, 'age-keygen'), '#!/bin/sh\nprintf "# public key: age1credentialtest\\n" > "$2"\n', { mode: 0o700 });
    writeFileSync(path.join(f.bin, 'age-keygen.cmd'), '@echo off\r\necho # public key: age1credentialtest> "%~2"\r\n');
    const envFile = path.join(f.directory, 'generated.env');
    const secretsDir = path.join(f.directory, 'custom secrets');
    const identity = path.join(f.directory, 'identity');
    const env = { ...f.env, ADMIN_PASSWORD: secret(), SMC_PUBLIC_URL: 'https://fixture.invalid',
      SECRETS_DIR: portable(secretsDir), AGE_IDENTITY_FILE: portable(identity) };
    const result = runtime === 'bash'
      ? execute(bash, ['scripts/prod/init-production-env.sh', portable(envFile)], env)
      : execute('pwsh', ['-NoProfile', '-File', 'scripts/prod/init-production-env.ps1', '-EnvFile', envFile,
        '-SecretsDirectory', secretsDir, '-AgeIdentityFile', identity, '-PublicUrl', 'https://fixture.invalid',
        '-AdminPassword', env.ADMIN_PASSWORD], env);
    assert.equal(result.status, 0, 'generator must succeed with a local age-keygen stub');
    const generated = Object.fromEntries(readFileSync(envFile, 'utf8').split(/\r?\n/)
      .filter(line => /^[A-Z_]+=/.test(line)).map(line => [line.slice(0, line.indexOf('=')), line.slice(line.indexOf('=') + 1)]));
    const passwords = passwordNames.map(name => generated[name]);
    assert.ok(passwords.every(value => /^[a-f0-9]{64}$/.test(value ?? '')), 'all four inline credentials must be random tokens');
    assert.equal(new Set(passwords).size, 4, 'each database role must have an independent credential');
    passwordNames.forEach(name => {
      const filename = generated[`${name}_FILE`];
      assert.ok(Boolean(filename), 'each role must have an explicit credential file');
      const resolved = path.resolve(root, 'deploy/compose', filename);
      assert.equal(path.dirname(resolved), path.resolve(secretsDir), 'configured files must use the requested directory');
      assert.ok(readFileSync(resolved, 'utf8') === generated[name], 'inline and file credentials must agree');
    });
    noLeaks(result.output, [...passwords, generated.ADMIN_PASSWORD, generated.TYPESENSE_API_KEY, generated.SMC_SECRETS_KEY]);
  });
}

for (const target of ['init', 'bootstrap']) {
  const script = target === 'init' ? 'deploy/images/postgres/init-roles.sh' : 'deploy/images/backup/bootstrap-role.sh';
  test(`${target} passes distinct credentials to SQL`, t => {
    const f = fixture(t);
    const result = execute(bash, [script], f.env);
    assert.equal(result.status, 0, 'valid role configuration must succeed');
    assert.ok(existsSync(f.marker), 'validated configuration must reach SQL');
    noLeaks(result.output, f.values);
  });
  for (const [left, right] of [[0, 1], [0, 2], [0, 3], [1, 2], [1, 3], [2, 3]]) {
    test(`${target} rejects shared credentials for roles ${left} and ${right} before SQL`, t => {
      const f = fixture(t);
      const inputNames = target === 'init'
        ? ['POSTGRES_PASSWORD', 'MIGRATE_DB_PASSWORD', 'APP_DB_PASSWORD', 'BACKUP_DB_PASSWORD']
        : ['PGPASSWORD_FILE', 'MIGRATE_DB_PASSWORD_FILE', 'APP_DB_PASSWORD_FILE', 'BACKUP_DB_PASSWORD_FILE'];
      if (target === 'init') f.env[inputNames[right]] = f.env[inputNames[left]];
      else {
        writeFileSync(f.env[inputNames[right]], f.values[left]);
        for (const name of [...passwordNames, 'APP_DB_PASSWORD']) delete f.env[name];
      }
      const result = execute(bash, [script], f.env);
      assert.notEqual(result.status, 0, 'shared database credentials must fail');
      assert.ok(!existsSync(f.marker), 'invalid credentials must not reach SQL');
      noLeaks(result.output, f.values);
    });
  }
  for (const index of [0, 1, 2, 3]) {
    test(`${target} rejects missing credential ${index} without a privileged fallback`, t => {
      const f = fixture(t);
      const inputs = target === 'init'
        ? ['POSTGRES_PASSWORD', 'MIGRATE_DB_PASSWORD', 'APP_DB_PASSWORD', 'BACKUP_DB_PASSWORD']
        : ['PGPASSWORD_FILE', 'MIGRATE_DB_PASSWORD_FILE', 'APP_DB_PASSWORD_FILE', 'BACKUP_DB_PASSWORD_FILE'];
      delete f.env[inputs[index]];
      const result = execute(bash, [script], f.env);
      assert.notEqual(result.status, 0, 'missing credentials must fail');
      assert.ok(!existsSync(f.marker), 'missing credentials must not reach SQL');
      noLeaks(result.output, f.values);
    });
    test(`${target} rejects empty credential ${index} before SQL`, t => {
      const f = fixture(t);
      const inputs = target === 'init'
        ? ['POSTGRES_PASSWORD', 'MIGRATE_DB_PASSWORD', 'APP_DB_PASSWORD', 'BACKUP_DB_PASSWORD']
        : ['PGPASSWORD_FILE', 'MIGRATE_DB_PASSWORD_FILE', 'APP_DB_PASSWORD_FILE', 'BACKUP_DB_PASSWORD_FILE'];
      if (target === 'init') f.env[inputs[index]] = '';
      else {
        writeFileSync(f.env[inputs[index]], '');
        for (const name of [...passwordNames, 'APP_DB_PASSWORD']) delete f.env[name];
      }
      const result = execute(bash, [script], f.env);
      assert.notEqual(result.status, 0, 'empty credentials must fail');
      assert.ok(!existsSync(f.marker), 'empty credentials must not reach SQL');
      noLeaks(result.output, f.values);
    });
  }
  test(`${target} rejects overlapping role names before SQL`, t => {
    const f = fixture(t);
    f.env.MIGRATE_DB_USER = f.env.POSTGRES_USER;
    const result = execute(bash, [script], f.env);
    assert.notEqual(result.status, 0, 'role names must be distinct');
    assert.ok(!existsSync(f.marker), 'overlapping roles must not reach SQL');
  });
}

test('bootstrap rejects inline credentials that disagree with credential files before changing passwords', t => {
  const f = fixture(t);
  f.env.APP_DB_PASSWORD = secret();
  const result = execute(bash, ['deploy/images/backup/bootstrap-role.sh'], f.env);
  assert.notEqual(result.status, 0, 'inconsistent credential sources must fail');
  assert.ok(!existsSync(f.marker), 'inconsistent sources must not reach SQL');
  noLeaks(result.output, [...f.values, f.env.APP_DB_PASSWORD]);
});

test('bootstrap accepts distinct file credentials without inline values', t => {
  const f = fixture(t);
  for (const name of [...passwordNames, 'APP_DB_PASSWORD']) delete f.env[name];
  const result = execute(bash, ['deploy/images/backup/bootstrap-role.sh'], f.env);
  assert.equal(result.status, 0, 'file-only bootstrap must remain supported');
  assert.ok(existsSync(f.marker), 'validated file credentials must reach SQL');
  noLeaks(result.output, f.values);
});

test('bootstrap refuses an unreadable migrator credential path instead of using the admin password', t => {
  const f = fixture(t);
  f.env.MIGRATE_DB_PASSWORD_FILE = portable(path.join(f.directory, 'missing'));
  const result = execute(bash, ['deploy/images/backup/bootstrap-role.sh'], f.env);
  assert.notEqual(result.status, 0, 'unreadable credentials must fail');
  assert.ok(!existsSync(f.marker), 'unreadable credentials must not reach SQL');
  noLeaks(result.output, f.values);
});

for (const runtime of ['bash', 'powershell']) {
  test(`${runtime} local migrations use the migrator password while warehouse keeps the runtime password`, t => {
    const f = fixture(t);
    const checkout = path.join(f.directory, 'checkout');
    const scriptDir = path.join(checkout, 'scripts/dev');
    const targetDir = path.join(checkout, 'apps/server/target');
    mkdirSync(scriptDir, { recursive: true });
    mkdirSync(targetDir, { recursive: true });
    writeFileSync(path.join(targetDir, 'server-fixture-exec.jar'), '');
    const extension = runtime === 'bash' ? 'sh' : 'ps1';
    const script = path.join(scriptDir, `run-local.${extension}`);
    copyFileSync(path.join(root, `scripts/dev/run-local.${extension}`), script);
    writeFileSync(path.join(f.bin, 'docker'), '#!/bin/sh\nexit 0\n', { mode: 0o700 });
    writeFileSync(path.join(f.bin, 'docker.cmd'), '@exit /b 0\r\n');
    writeFileSync(path.join(f.bin, 'java'), '#!/bin/sh\nexec node "$(dirname "$0")/java-stub.mjs" "$@"\n', { mode: 0o700 });
    writeFileSync(path.join(f.bin, 'java.cmd'), '@node "%~dp0java-stub.mjs" %*\r\n');
    const javaLog = path.join(f.directory, 'java-events');
    writeFileSync(path.join(f.bin, 'java-stub.mjs'), `import { appendFileSync } from 'node:fs';
if (process.argv.includes('-version')) { console.log('java version "25"'); }
else {
  const valid = process.env.DB_USER === 'smartupcms_migrator'
    && process.env.DB_PASSWORD === process.env.EXPECTED_MIGRATE
    && process.env.WAREHOUSE_PASSWORD === process.env.EXPECTED_APP;
  if (!valid) process.exit(75);
  appendFileSync(process.env.JAVA_EVENTS, process.env.SMC_MIGRATE_SCOPE === 'warehouse' ? 'warehouse\\n' : 'cms\\n');
}
`);
    const env = { ...f.env, JAVA_HOME: '', JAVA_EVENTS: portable(javaLog) };
    delete env.MSYS_NO_PATHCONV;
    const result = runtime === 'bash'
      ? execute(bash, [portable(script), 'migrate', '--skip-build'], env, checkout)
      : execute('pwsh', ['-NoProfile', '-File', script, 'migrate', '-SkipBuild'], env, checkout);
    assert.equal(result.status, 0, 'local migrations must authenticate with distinct role credentials');
    assert.equal(readFileSync(javaLog, 'utf8'), 'warehouse\ncms\n', 'both migration phases must run with the expected credentials');
    noLeaks(result.output, f.values);
  });
}

function composeFixture(t) {
  const f = fixture(t);
  Object.assign(f.env, {
    APP_VERSION: 'credential-test', DB_USER: 'smartupcms', ORGANIZATION_NAME: 'Fixture',
    TYPESENSE_API_KEY: secret(), SMC_SECRETS_KEY: secret(), SMC_PUBLIC_URL: 'https://fixture.invalid',
    BACKUP_AGE_RECIPIENT: 'age1credentialtest', POSTGRES_PASSWORD_FILE: f.env.PGPASSWORD_FILE,
    DB_PASSWORD_FILE: f.env.APP_DB_PASSWORD_FILE,
  });
  const envFile = path.join(f.directory, 'compose.env');
  writeFileSync(envFile, '');
  const args = ['compose', '-f', 'deploy/compose/docker-compose.prod.yml', '--env-file', envFile,
    '--profile', 'tools', 'config', '--format', 'json'];
  return { ...f, args };
}

for (const name of ['POSTGRES_PASSWORD', 'MIGRATE_DB_PASSWORD', 'BACKUP_DB_PASSWORD', 'POSTGRES_PASSWORD_FILE', 'MIGRATE_DB_PASSWORD_FILE']) {
  test(`production Compose refuses missing ${name} instead of using the runtime credential`, t => {
    const f = composeFixture(t);
    delete f.env[name];
    const result = execute('docker', f.args, f.env);
    assert.notEqual(result.status, 0, 'each privileged or backup credential source must be explicit');
    noLeaks(result.output, f.values);
  });
}

test('production Compose binds bootstrap checks to the same credentials as the database and application', t => {
  const f = composeFixture(t);
  const result = execute('docker', f.args, f.env);
  assert.equal(result.status, 0, 'complete production configuration must render');
  const config = JSON.parse(result.stdout);
  const postgres = config.services.postgres.environment;
  const bootstrap = config.services['backup-bootstrap'].environment;
  for (const name of ['POSTGRES_PASSWORD', 'MIGRATE_DB_PASSWORD', 'APP_DB_PASSWORD', 'BACKUP_DB_PASSWORD']) {
    assert.ok(bootstrap[name] === postgres[name], 'bootstrap must validate the exact credential used at initialization');
  }
  assert.ok(config.services.server.environment.DB_PASSWORD === postgres.APP_DB_PASSWORD, 'server must use the runtime credential');
  assert.ok(config.services.migrate.environment.DB_PASSWORD === postgres.MIGRATE_DB_PASSWORD, 'migrations must use the migrator credential');
  const files = ['postgres_password', 'migrate_database_password', 'database_password', 'backup_database_password']
    .map(name => config.secrets[name].file);
  assert.equal(new Set(files).size, 4,
    'role credential files must be independent');
});

test('development Compose defaults preserve distinct role credentials and both migration connections', t => {
  const f = composeFixture(t);
  f.args[2] = 'docker-compose.yml';
  for (const name of [...passwordNames, 'APP_DB_PASSWORD']) delete f.env[name];
  const result = execute('docker', f.args, f.env);
  assert.equal(result.status, 0, 'development configuration must render without password overrides');
  const config = JSON.parse(result.stdout);
  const postgres = config.services.postgres.environment;
  const values = ['POSTGRES_PASSWORD', 'MIGRATE_DB_PASSWORD', 'APP_DB_PASSWORD', 'BACKUP_DB_PASSWORD']
    .map(name => postgres[name]);
  assert.equal(new Set(values).size, 4, 'development role passwords must remain independent');
  assert.ok(config.services.migrate.environment.DB_PASSWORD === postgres.MIGRATE_DB_PASSWORD, 'development migrations use the migrator credential');
  assert.ok(config.services.migrate.environment.WAREHOUSE_PASSWORD === postgres.APP_DB_PASSWORD, 'warehouse keeps its runtime owner');
});
