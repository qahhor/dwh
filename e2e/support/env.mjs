import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const currentDirectory = dirname(fileURLToPath(import.meta.url));
const defaultEnvFilePath = resolve(currentDirectory, '..', '..', '.env');

function parseEnvFile(contents) {
  const parsed = {};

  for (const sourceLine of contents.split(/\r?\n/u)) {
    const line = sourceLine.trim();
    if (!line || line.startsWith('#')) continue;

    const separator = line.indexOf('=');
    if (separator < 1) continue;

    const key = line.slice(0, separator).trim();
    let value = line.slice(separator + 1).trim();
    if ((value.startsWith('"') && value.endsWith('"'))
      || (value.startsWith("'") && value.endsWith("'"))) {
      value = value.slice(1, -1);
    }
    parsed[key] = value;
  }

  return parsed;
}

function required(source, key) {
  const value = source[key]?.trim();
  if (!value) {
    throw new Error(`Missing required E2E configuration key: ${key}`);
  }
  return value;
}

export function loadE2eEnv({
  envFilePath = defaultEnvFilePath,
  processEnv = process.env,
} = {}) {
  const fileEnv = existsSync(envFilePath)
    ? parseEnvFile(readFileSync(envFilePath, 'utf8'))
    : {};
  const source = { ...fileEnv, ...processEnv };

  return Object.freeze({
    instance: Object.freeze({
      baseURL: source.INSTANCE_BASE_URL?.trim() || 'http://localhost:4200',
      login: source.ADMIN_LOGIN?.trim() || 'admin',
      password: required(source, 'ADMIN_PASSWORD'),
    }),
    controlPlane: Object.freeze({
      baseURL: source.CP_BASE_URL?.trim() || 'http://localhost:4300',
      login: source.CP_ADMIN_LOGIN?.trim() || 'cpadmin',
      password: required(source, 'CP_ADMIN_PASSWORD'),
    }),
  });
}
