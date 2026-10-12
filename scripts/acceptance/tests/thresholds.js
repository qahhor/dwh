import { check } from 'k6';
import { Rate, Trend } from 'k6/metrics';
import { standardThresholds } from '../load/common.js';

const duration = new Trend('acceptance_duration', true);
const failed = new Rate('acceptance_failed');
const thresholds = Object.fromEntries(
  Object.entries(standardThresholds('boundary')).map(([name, values]) => [
    name.replace('http_req_duration', 'acceptance_duration').replace('http_req_failed', 'acceptance_failed'),
    values,
  ]),
);

export const options = {
  scenarios: { boundary: { executor: 'shared-iterations', vus: 1, iterations: 1 } },
  thresholds,
};

export default function () {
  duration.add(Number(__ENV.PROBE_LATENCY_MS));
  const requestFailed = __ENV.PROBE_FAILED === 'true';
  failed.add(requestFailed);
  check(requestFailed, { 'request succeeded': (value) => !value });
}
