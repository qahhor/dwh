import http from 'k6/http';
import { sleep } from 'k6';
import {
  ACCEPTANCE_MAX_ERROR_RATE,
  ACCEPTANCE_MAX_P95_MS,
  ACCEPTANCE_MAX_P99_MS,
  authHeaders,
  baseUrl,
  expectStatus,
  standardThresholds,
} from './common.js';

const activeUsers = Number(__ENV.ACTIVE_USERS || '100');
const duration = __ENV.SOAK_DURATION || '4h';

export const options = {
  discardResponseBodies: true,
  scenarios: {
    soak: {
      executor: 'constant-vus',
      vus: activeUsers,
      duration,
      gracefulStop: '1m',
    },
  },
  thresholds: standardThresholds('soak'),
};

void ACCEPTANCE_MAX_P95_MS;
void ACCEPTANCE_MAX_P99_MS;
void ACCEPTANCE_MAX_ERROR_RATE;

export default function () {
  const tasks = http.get(`${baseUrl}/api/v1/tasks/items?limit=20`, {
    headers: authHeaders(),
    tags: { name: 'soak-tasks-list' },
  });
  expectStatus(tasks, 200, 'soak-tasks-list');

  const notifications = http.get(`${baseUrl}/api/v1/notifications/inbox?limit=20`, {
    headers: authHeaders(),
    tags: { name: 'soak-notifications' },
  });
  expectStatus(notifications, 200, 'soak-notifications');
  sleep(2);
}
