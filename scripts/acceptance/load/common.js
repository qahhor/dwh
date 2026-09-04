import http from 'k6/http';
import { check, fail } from 'k6';
import { SharedArray } from 'k6/data';
import exec from 'k6/execution';

export const ACCEPTANCE_MAX_P95_MS = requiredNumber('ACCEPTANCE_MAX_P95_MS', 1);
export const ACCEPTANCE_MAX_P99_MS = requiredNumber('ACCEPTANCE_MAX_P99_MS', 1);
export const ACCEPTANCE_MAX_ERROR_RATE = requiredNumber('ACCEPTANCE_MAX_ERROR_RATE', 0, 1);

export const baseUrl = required('ACCEPTANCE_PUBLIC_ORIGIN').replace(/\/$/, '');
if (!baseUrl.startsWith('https://')) {
  throw new Error('Capacity acceptance requires an HTTPS public origin.');
}

const users = new SharedArray('acceptance-api-tokens', () => {
  const parsed = JSON.parse(open(required('LOAD_USERS_FILE')));
  if (!Array.isArray(parsed) || parsed.length === 0) {
    throw new Error('LOAD_USERS_FILE must contain a non-empty JSON array.');
  }
  for (const user of parsed) {
    if (typeof user.token !== 'string' || user.token.trim().length < 20) {
      throw new Error('Every load user must contain a non-empty API token.');
    }
  }
  return parsed;
});

export function required(name) {
  const value = __ENV[name];
  if (typeof value !== 'string' || value.trim() === '') {
    throw new Error(`Required environment value ${name} is missing.`);
  }
  return value.trim();
}

export function requiredNumber(name, minimum, maximum = Number.POSITIVE_INFINITY) {
  const value = Number(required(name));
  if (!Number.isFinite(value) || value < minimum || value > maximum) {
    throw new Error(`${name} must be a number between ${minimum} and ${maximum}.`);
  }
  return value;
}

export function authHeaders(extra = {}) {
  const user = users[(exec.vu.idInTest - 1) % users.length];
  return {
    Authorization: `Bearer ${user.token}`,
    Accept: 'application/json',
    ...extra,
  };
}

export function requestName(name) {
  return { tags: { name } };
}

export function expectStatus(response, expected, label) {
  const ok = check(response, {
    [`${label}: HTTP ${expected}`]: (result) => result.status === expected,
  });
  if (!ok && __ENV.ABORT_ON_CHECK_FAILURE === 'true') {
    fail(`${label} returned HTTP ${response.status}.`);
  }
  return ok;
}

export function jsonRequest(method, path, body, expected, name) {
  const response = http.request(method, `${baseUrl}${path}`, body === null ? null : JSON.stringify(body), {
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    tags: { name },
  });
  expectStatus(response, expected, name);
  return response;
}

export function standardThresholds(scenarioName) {
  return {
    [`http_req_duration{scenario:${scenarioName}}`]: [
      `p(95)<${ACCEPTANCE_MAX_P95_MS}`,
      `p(99)<${ACCEPTANCE_MAX_P99_MS}`,
    ],
    [`http_req_failed{scenario:${scenarioName}}`]: [`rate<${ACCEPTANCE_MAX_ERROR_RATE}`],
    checks: [`rate>${1 - ACCEPTANCE_MAX_ERROR_RATE}`],
  };
}
