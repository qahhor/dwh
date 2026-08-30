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
    'CP_ADMIN_LOGIN=file-cp-user',
    'CP_ADMIN_PASSWORD=file-cp-secret',
  ].join('\n'));

  const config = loadE2eEnv({
    envFilePath,
    processEnv: {
      ADMIN_PASSWORD: 'process-instance-secret',
      CP_ADMIN_LOGIN: 'process-cp-user',
      CP_ADMIN_PASSWORD: 'process-cp-secret',
    },
  });

  assert.equal(config.instance.password, 'process-instance-secret');
  assert.equal(config.controlPlane.login, 'process-cp-user');
  assert.equal(config.controlPlane.password, 'process-cp-secret');
});

test('local env file supplies credentials and quoted values', () => {
  const envFilePath = temporaryEnvFile([
    'ADMIN_PASSWORD="instance secret"',
    "CP_ADMIN_LOGIN='cpadmin'",
    'CP_ADMIN_PASSWORD=cp-secret',
  ].join('\n'));

  const config = loadE2eEnv({ envFilePath, processEnv: {} });

  assert.deepEqual(config.instance, {
    baseURL: 'http://localhost:4200',
    login: 'admin',
    password: 'instance secret',
  });
  assert.deepEqual(config.controlPlane, {
    baseURL: 'http://localhost:4300',
    login: 'cpadmin',
    password: 'cp-secret',
  });
});

test('missing credentials fail with key names and never expose present secrets', () => {
  assert.throws(
    () => loadE2eEnv({
      envFilePath: temporaryEnvFile('ADMIN_PASSWORD=must-not-leak'),
      processEnv: {},
    }),
    (error) => {
      assert.match(error.message, /CP_ADMIN_PASSWORD/);
      assert.doesNotMatch(error.message, /must-not-leak/);
      return true;
    },
  );
});
