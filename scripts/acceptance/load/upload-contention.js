import http from 'k6/http';
import {
  ACCEPTANCE_MAX_ERROR_RATE,
  ACCEPTANCE_MAX_P95_MS,
  ACCEPTANCE_MAX_P99_MS,
  authHeaders,
  baseUrl,
  expectStatus,
  required,
  standardThresholds,
} from './common.js';

const uploadUsers = Number(__ENV.UPLOAD_USERS || '20');
const fixture = open(required('UPLOAD_FIXTURE'), 'b');

export const options = {
  discardResponseBodies: true,
  scenarios: {
    upload_contention: {
      executor: 'per-vu-iterations',
      vus: uploadUsers,
      iterations: 1,
      maxDuration: '15m',
    },
  },
  thresholds: standardThresholds('upload_contention'),
};

void ACCEPTANCE_MAX_P95_MS;
void ACCEPTANCE_MAX_P99_MS;
void ACCEPTANCE_MAX_ERROR_RATE;

export default function () {
  const response = http.post(`${baseUrl}/api/v1/files/upload`, {
    file: http.file(fixture, `near-limit-${__VU}.pdf`, 'application/pdf'),
  }, {
    headers: authHeaders(),
    tags: { name: 'near-limit-upload' },
    timeout: '10m',
  });
  expectStatus(response, 201, 'near-limit-upload');
}
