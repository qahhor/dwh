import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';

import { loadE2eEnv } from '../../support/env.mjs';

function temporaryEnvFile(contents) {
  const directory = mkdtempSync(join(tmpdir(), 'smartupcms-e2e-'));
  const file = join(directory, '.env');
  writeFileSync(file, contents, 'utf8');
  return file;
}

test('process environment wins over the local env file', () => {
  const envFilePath = temporaryEnvFile([
    'ADMIN_PASSWORD=file-instance-secret',
  ].join('\n'));

  const config = loadE2eEnv({
    envFilePath,
    processEnv: {
      ADMIN_PASSWORD: 'process-instance-secret',
    },
  });

  assert.equal(config.instance.password, 'process-instance-secret');
});

test('local env file supplies credentials and quoted values', () => {
  const envFilePath = temporaryEnvFile([
    'ADMIN_PASSWORD="instance secret"',
  ].join('\n'));

  const config = loadE2eEnv({ envFilePath, processEnv: {} });

  assert.deepEqual(config.instance, {
    baseURL: 'http://localhost:4200',
    login: 'admin',
    password: 'instance secret',
  });
});

test('the mail stub address defaults to the published Mailpit port and follows MAILPIT_URL', () => {
  const envFilePath = temporaryEnvFile('ADMIN_PASSWORD=instance-secret');

  assert.equal(loadE2eEnv({ envFilePath, processEnv: {} }).mailpit.baseURL, 'http://localhost:8025');
  assert.equal(
    loadE2eEnv({ envFilePath, processEnv: { MAILPIT_URL: 'http://127.0.0.1:58025' } }).mailpit.baseURL,
    'http://127.0.0.1:58025',
  );
});

test('missing credentials fail with key names and never expose present secrets', () => {
  assert.throws(
    () => loadE2eEnv({
      envFilePath: temporaryEnvFile('UNRELATED_SECRET=must-not-leak'),
      processEnv: {},
    }),
    (error) => {
      assert.match(error.message, /ADMIN_PASSWORD/);
      assert.doesNotMatch(error.message, /must-not-leak/);
      return true;
    },
  );
});
