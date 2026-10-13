import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, readFileSync, writeFileSync, existsSync, rmSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const root = fileURLToPath(new URL('../..', import.meta.url));
const bash = process.platform === 'win32' ? 'C:/Program Files/Git/bin/bash.exe' : 'bash';
const portable = value => value.replaceAll('\\', '/');

function fixture(t) {
  const local = path.join(root, '.local');
  mkdirSync(local, { recursive: true });
  const directory = mkdtempSync(path.join(local, 'backup-control-'));
  t.after(() => {
    assert.ok(path.resolve(directory).startsWith(path.resolve(local) + path.sep));
    rmSync(directory, { recursive: true, force: true });
  });
  const bin = path.join(directory, 'bin');
  const status = path.join(directory, 'status');
  mkdirSync(bin); mkdirSync(status);
  const env = { ...process.env, MSYS_NO_PATHCONV: '1' };
  env.SMC_TEST_BIN = portable(bin).replace(/^([A-Za-z]):/, (_, drive) => '/' + drive.toLowerCase());
  const pathKey = Object.keys(env).find(key => key.toLowerCase() === 'path') ?? 'PATH';
  env[pathKey] = bin + path.delimiter + env[pathKey];
  Object.assign(env, {
    BACKUP_DIR: portable(path.join(directory, 'backups')),
    BACKUP_STATUS_FILE: portable(path.join(status, 'status.json')),
    PGPASSWORD_FILE: portable(path.join(directory, 'password')),
    BACKUP_RUN_ONCE: 'true', BACKUP_COORDINATED_ONLY: 'false', BACKUP_STORAGE_MODE: 'local',
    AGE_RECIPIENT: 'age1fixture', PGDATABASE: 'smartupcms', WAREHOUSE_DB_NAME: 'smartupcms_dwh',
    BACKUP_TIMESTAMP: '20261013T000000Z', BACKUP_CONSISTENT_POINT: '2026-10-13T00:00:00Z',
    DUMP_MARKER: portable(path.join(directory, 'dump-called')),
  });
  writeFileSync(env.PGPASSWORD_FILE, 'disposable-fixture');
  writeFileSync(path.join(bin, 'pg_dump'), '#!/bin/sh\n[ -f "$(dirname "$BACKUP_STATUS_FILE")/running" ] || exit 71\ntouch "$DUMP_MARKER"\nprintf encrypted-fixture\n', { mode: 0o700 });
  writeFileSync(path.join(bin, 'age'), '#!/bin/sh\ncat\n', { mode: 0o700 });
  writeFileSync(path.join(bin, 'sleep'), '#!/bin/sh\nexit 23\n', { mode: 0o700 });
  writeFileSync(path.join(bin, 'write-status'), '#!/bin/sh\nprintf "%s" "$1" > "$BACKUP_STATUS_FILE"\n', { mode: 0o700 });
  const script = path.join(directory, 'backup-loop.sh');
  writeFileSync(script, readFileSync(path.join(root, 'deploy/images/backup/backup-loop.sh'), 'utf8')
    .replace('/usr/local/bin/write-status', 'write-status'));
  return { env, script, status };
}

function run(f) {
  const result = spawnSync(bash, ['-c', 'export PATH="$SMC_TEST_BIN:$PATH"; exec bash "$1"', 'backup-test', portable(f.script)], { env: f.env, encoding: 'utf8', timeout: 15000 });
  assert.equal(result.error, undefined);
  return result;
}

test('one-shot captures both databases at explicit point and clears running marker', t => {
  const f = fixture(t);
  assert.equal(run(f).status, 0);
  assert.equal(readFileSync(path.join(f.status, 'control-version'), 'utf8').trim(), '1');
  assert.equal(existsSync(path.join(f.status, 'running')), false);
  for (const name of ['smartupcms', 'smartupcms_dwh']) {
    const manifest = JSON.parse(readFileSync(path.join(f.env.BACKUP_DIR, name + '-' + f.env.BACKUP_TIMESTAMP + '.dump.age.manifest.json')));
    assert.equal(manifest.capturedAt, f.env.BACKUP_CONSISTENT_POINT);
  }
});

for (const mode of ['paused', 'coordinated']) {
  test(mode + ' worker never begins an autonomous dump', t => {
    const f = fixture(t);
    f.env.BACKUP_RUN_ONCE = 'false';
    if (mode === 'paused') writeFileSync(path.join(f.status, 'pause'), '');
    else f.env.BACKUP_COORDINATED_ONLY = 'true';
    assert.equal(run(f).status, 23);
    assert.equal(existsSync(f.env.DUMP_MARKER), false);
    assert.equal(existsSync(path.join(f.status, 'running')), false);
  });
}

test('invalid supplied timestamp fails before dump', t => {
  const f = fixture(t);
  f.env.BACKUP_TIMESTAMP = '../escape';
  assert.notEqual(run(f).status, 0);
  assert.equal(existsSync(f.env.DUMP_MARKER), false);
  assert.equal(existsSync(path.join(f.status, 'running')), false);
});
