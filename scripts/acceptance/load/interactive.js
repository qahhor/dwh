import http from 'k6/http';
import { sleep } from 'k6';
import {
  ACCEPTANCE_MAX_ERROR_RATE,
  ACCEPTANCE_MAX_P95_MS,
  ACCEPTANCE_MAX_P99_MS,
  authHeaders,
  baseUrl,
  expectStatus,
  jsonRequest,
  standardThresholds,
} from './common.js';

const activeUsers = Number(__ENV.ACTIVE_USERS || '100');
const duration = __ENV.INTERACTIVE_DURATION || '15m';

export const options = {
  discardResponseBodies: true,
  scenarios: {
    interactive: {
      executor: 'constant-vus',
      vus: activeUsers,
      duration,
      gracefulStop: '30s',
    },
  },
  thresholds: standardThresholds('interactive'),
};

// Keep the approved thresholds visible in this standalone profile contract.
void ACCEPTANCE_MAX_P95_MS;
void ACCEPTANCE_MAX_P99_MS;
void ACCEPTANCE_MAX_ERROR_RATE;

export default function () {
  const taskList = http.get(`${baseUrl}/api/v1/tasks/items?limit=20`, {
    headers: authHeaders(),
    tags: { name: 'tasks-list' },
  });
  expectStatus(taskList, 200, 'tasks-list');

  const files = http.get(`${baseUrl}/api/v1/files?limit=20`, {
    headers: authHeaders(),
    tags: { name: 'files-list' },
  });
  expectStatus(files, 200, 'files-list');

  const analytics = http.get(`${baseUrl}/api/v1/analytics/summary`, {
    headers: authHeaders(),
    tags: { name: 'analytics-summary' },
  });
  expectStatus(analytics, 200, 'analytics-summary');

  if (__ENV.ACCEPTANCE_LOAD_WRITES === 'true' && __ITER % 10 === 0) {
    jsonRequest('POST', '/api/v1/tasks/items', {
      title: `acceptance-${__VU}-${__ITER}-${Date.now()}`,
      priority: 'normal',
      descriptionMarkdown: 'Managed infrastructure capacity acceptance record.',
    }, 201, 'task-create');
  }

  sleep(1);
}
