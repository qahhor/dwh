import { randomBytes, randomUUID } from 'node:crypto';
import { writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const PROTECTED_PORTS = new Set(['4200', '14200', '14203', '14204', '14205', '14206']);
const ARGUMENTS = Object.freeze({
  tasks: { minimum: 1, maximum: 3000, fallback: 300 },
  projects: { minimum: 1, maximum: 300, fallback: 30 },
  users: { minimum: 1, maximum: 100, fallback: 10 },
});
const FIXED_SEED = 'smartupcms-search-benchmark-v1';
const MEASURED_REQUESTS = 30;
const SEARCH_ENTITIES = ['TASK', 'PROJECT', 'USER'];
const REQUEST_TIMEOUT_MS = 30_000;
const SEARCH_POLL_FLOOR_MS = 2_500;
// Settings, status and job creation share the fixed 10/minute management family.
// Ten seconds leaves room for those setup calls plus bounded job observation.
const MANAGEMENT_POLL_FLOOR_MS = 10_000;
const RETRY_AFTER_CAP_MS = 60_000;

function requiredEnvironment(environment, name) {
  const value = environment[name];
  if (typeof value !== 'string' || value.trim() === '') {
    throw new Error(`${name} is required`);
  }
  return value;
}

function parseInteger(name, rawValue, { minimum, maximum, fallback }) {
  if (rawValue === undefined) return fallback;
  if (!/^[0-9]+$/u.test(rawValue)) {
    throw new Error(`--${name} must be an integer from ${minimum} to ${maximum}`);
  }
  const value = Number(rawValue);
  if (!Number.isSafeInteger(value) || value < minimum || value > maximum) {
    throw new Error(`--${name} must be an integer from ${minimum} to ${maximum}`);
  }
  return value;
}

function parseArguments(argv) {
  const values = new Map();
  for (const argument of argv) {
    const match = /^--([a-z-]+)=(.*)$/u.exec(argument);
    if (!match || !['tasks', 'projects', 'users', 'concurrency', 'mode', 'output'].includes(match[1])) {
      throw new Error('Unsupported benchmark argument');
    }
    if (values.has(match[1])) throw new Error(`--${match[1]} may be supplied only once`);
    values.set(match[1], match[2]);
  }
  return values;
}

function validateOrigin(rawOrigin) {
  let target;
  try {
    target = new URL(rawOrigin);
  } catch {
    throw new Error('SEARCH_BENCHMARK_ORIGIN must be a bare loopback HTTP origin on an owned non-protected port');
  }
  const loopback = ['127.0.0.1', 'localhost', '[::1]'].includes(target.hostname);
  const bareOrigin = target.origin === rawOrigin && target.pathname === '/' && !target.search && !target.hash;
  if (target.protocol !== 'http:' || !loopback || !target.port || PROTECTED_PORTS.has(target.port)
      || target.username || target.password || !bareOrigin) {
    throw new Error('SEARCH_BENCHMARK_ORIGIN must be a bare loopback HTTP origin on an owned non-protected port');
  }
  return target.origin;
}

export function validateBenchmarkConfiguration(argv = [], environment = {}) {
  const origin = validateOrigin(requiredEnvironment(environment, 'SEARCH_BENCHMARK_ORIGIN'));
  const login = requiredEnvironment(environment, 'SEARCH_BENCHMARK_LOGIN');
  const password = requiredEnvironment(environment, 'SEARCH_BENCHMARK_PASSWORD');
  const argumentsByName = parseArguments(argv);
  const concurrency = parseInteger('concurrency', argumentsByName.get('concurrency'), {
    minimum: 1, maximum: 5, fallback: 1,
  });
  if (![1, 5].includes(concurrency)) throw new Error('--concurrency must be 1 or 5');
  const mode = argumentsByName.get('mode') ?? 'current';
  if (!['current', 'baseline'].includes(mode)) throw new Error('--mode must be current or baseline');
  const output = argumentsByName.get('output') ?? null;
  if (output !== null && output.trim() === '') throw new Error('--output requires a path');
  return {
    origin,
    login,
    password,
    counts: {
      tasks: parseInteger('tasks', argumentsByName.get('tasks'), ARGUMENTS.tasks),
      projects: parseInteger('projects', argumentsByName.get('projects'), ARGUMENTS.projects),
      users: parseInteger('users', argumentsByName.get('users'), ARGUMENTS.users),
    },
    concurrency,
    mode,
    output,
  };
}

class BenchmarkHttpError extends Error {
  constructor(method, path, status, retryAfterMs = null) {
    super(`${method} ${path} returned HTTP ${status}`);
    this.status = status;
    this.retryAfterMs = retryAfterMs;
  }
}

function retryAfterMilliseconds(rawValue, epochNow) {
  if (typeof rawValue !== 'string' || rawValue.trim() === '') return null;
  const value = rawValue.trim();
  if (/^[0-9]+$/u.test(value)) {
    const milliseconds = Number(value) * 1_000;
    return Number.isSafeInteger(milliseconds) ? Math.min(milliseconds, RETRY_AFTER_CAP_MS) : RETRY_AFTER_CAP_MS;
  }
  const timestamp = Date.parse(value);
  if (!Number.isFinite(timestamp)) return null;
  return Math.min(Math.max(0, timestamp - epochNow()), RETRY_AFTER_CAP_MS);
}

class BenchmarkClient {
  constructor(origin, fetchImpl, createRequestSignal, epochNow) {
    this.origin = origin;
    this.fetchImpl = fetchImpl;
    this.createRequestSignal = createRequestSignal;
    this.epochNow = epochNow;
    this.cookies = new Map();
  }

  captureCookies(headers) {
    const values = typeof headers.getSetCookie === 'function'
      ? headers.getSetCookie()
      : [headers.get('set-cookie')].filter(Boolean);
    for (const header of values) {
      const first = header.split(';', 1)[0];
      const separator = first.indexOf('=');
      if (separator > 0) this.cookies.set(first.slice(0, separator), first.slice(separator + 1));
    }
  }

  async request(method, path, { body, expected = [200] } = {}) {
    const headers = { accept: 'application/json' };
    if (this.cookies.size > 0) {
      headers.cookie = [...this.cookies].map(([name, value]) => `${name}=${value}`).join('; ');
    }
    if (body !== undefined) {
      headers['content-type'] = 'application/json';
      const csrf = this.cookies.get('XSRF-TOKEN');
      if (csrf) headers['x-xsrf-token'] = csrf;
    }
    const response = await this.fetchImpl(`${this.origin}${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      redirect: 'error',
      signal: this.createRequestSignal(REQUEST_TIMEOUT_MS),
    });
    this.captureCookies(response.headers);
    if (!expected.includes(response.status)) {
      await response.body?.cancel().catch(() => undefined);
      throw new BenchmarkHttpError(
        method,
        path,
        response.status,
        retryAfterMilliseconds(response.headers.get('retry-after'), this.epochNow),
      );
    }
    if (response.status === 204) return null;
    try {
      return await response.json();
    } catch {
      throw new Error(`${method} ${path} returned an invalid JSON response`);
    }
  }

  get(path, expected) {
    return this.request('GET', path, { expected });
  }

  post(path, body, expected) {
    return this.request('POST', path, { body, expected });
  }

  patch(path, body, expected) {
    return this.request('PATCH', path, { body, expected });
  }
}

function syntheticText(index) {
  return `SBV1 delivery маршрут Oʻzbekiston O'zbekiston ${String(index).padStart(4, '0')}`;
}

async function authenticate(client, configuration) {
  const login = await client.post('/api/v1/auth/login', {
    login: configuration.login,
    password: configuration.password,
    deviceInfo: 'bounded-search-benchmark',
  }, [200]);
  if (login?.step !== 'success') throw new Error('Benchmark credential requires an unsupported authentication step');
  if (login.user?.forcePasswordChange) {
    throw new Error('Benchmark credential must complete mandatory password change before the run');
  }
  if (!client.cookies.has('DWH_SESSION') || !client.cookies.has('XSRF-TOKEN')) {
    throw new Error('Benchmark authentication did not establish the required session and CSRF cookies');
  }
}

async function seedDataset(client, counts) {
  const projects = [];
  for (let index = 1; index <= counts.projects; index += 1) {
    const project = await client.post('/api/v1/tasks/projects', {
      name: `${syntheticText(index)} project`,
      description: `${FIXED_SEED} synthetic project`,
      state: 'A',
      attributes: {},
    }, [201]);
    projects.push(String(project.id));
  }

  const users = [];
  for (let index = 1; index <= counts.users; index += 1) {
    const login = `sbv1-user-${String(index).padStart(4, '0')}`;
    const user = await client.post('/api/v1/iam/users', {
      name: `${syntheticText(index)} user`,
      login,
      email: `${login}@example.invalid`,
      password: `Qa!7${randomBytes(20).toString('hex')}`,
      language: 'ru',
      timezone: 'Asia/Tashkent',
      is2faEnabled: false,
      forcePasswordChange: true,
      roleIds: [],
      attributes: {},
    }, [201]);
    users.push(String(user.id));
  }

  const tasks = [];
  for (let index = 1; index <= counts.tasks; index += 1) {
    const task = await client.post('/api/v1/tasks', {
      title: `${syntheticText(index)} task`,
      descriptionMarkdown: `${FIXED_SEED} synthetic task`,
      projectId: Number(projects[(index - 1) % projects.length]),
      priority: 'medium',
      attributes: { task_type: 'task' },
    }, [201]);
    tasks.push(String(task.id));
  }
  return { projects, users, tasks };
}

function queryMix(taskId) {
  return [
    { kind: 'russian', query: 'маршрут', entity: 'ALL' },
    { kind: 'latin', query: 'delivery', entity: 'ALL' },
    { kind: 'uzbek-modifier-apostrophe', query: 'Oʻzbekiston', entity: 'ALL' },
    { kind: 'uzbek-ascii-apostrophe', query: "O'zbekiston", entity: 'ALL' },
    { kind: 'typo', query: 'delivry', entity: 'ALL' },
    { kind: 'prefix', query: 'deli', entity: 'ALL' },
    { kind: 'exact-id', query: `#${taskId}`, entity: 'TASK' },
    { kind: 'no-match', query: 'sbv1-no-match-zzzzzz', entity: 'ALL' },
  ];
}

function searchPath(query, entity) {
  const parameters = new URLSearchParams({ q: query, entity, limit: '10' });
  return `/api/v1/search?${parameters}`;
}

async function executeSearchOperation(client, item, mode) {
  if (mode === 'baseline') {
    const responses = [];
    for (const entity of SEARCH_ENTITIES) {
      responses.push(await client.get(searchPath(item.query, entity), [200]));
    }
    return responses;
  }
  return [await client.get(searchPath(item.query, item.entity), [200])];
}

function percentile(sorted, quantile) {
  if (sorted.length === 0) return null;
  return sorted[Math.max(0, Math.ceil(sorted.length * quantile) - 1)];
}

function summarizeMeasurements(measurements) {
  const durations = measurements
    .filter(measurement => !measurement.error && measurement.matchedIndexed)
    .map(measurement => measurement.durationMs)
    .sort((left, right) => left - right);
  return {
    operations: measurements.length,
    latencyPopulation: 'successful healthy indexed matches only',
    latencySamples: durations.length,
    p50Ms: percentile(durations, 0.50),
    p95Ms: percentile(durations, 0.95),
    p99Ms: percentile(durations, 0.99),
    errors: measurements.filter(measurement => measurement.error).length,
    rateLimited: measurements.filter(measurement => measurement.status === 429).length,
    healthyResponses: measurements.reduce((count, measurement) => count + measurement.healthyResponses, 0),
    degradedResponses: measurements.reduce((count, measurement) => count + measurement.degradedResponses, 0),
  };
}

async function measuredScenario({ client, mix, mode, concurrency, spacingMs, sleep, now, epochNow }) {
  const measurements = [];
  for (let offset = 0; offset < MEASURED_REQUESTS; offset += concurrency) {
    if (offset > 0) await sleep(spacingMs * Math.min(concurrency, MEASURED_REQUESTS - offset));
    const batchSize = Math.min(concurrency, MEASURED_REQUESTS - offset);
    const batch = Array.from({ length: batchSize }, (_, batchIndex) => offset + batchIndex);
    const values = await Promise.all(batch.map(async (index) => {
      const item = mix[index % mix.length];
      const startedAt = now();
      const startedAtEpochMs = epochNow();
      try {
        const responses = await executeSearchOperation(client, item, mode);
        const matchedIndexed = responses.every(response => response?.source === 'TYPESENSE' && response?.degraded === false)
          && responses.some(response => Number(response?.totalHits ?? response?.foundHits ?? 0) > 0);
        const finishedAt = now();
        return {
          startedAt,
          finishedAt,
          startedAtEpochMs,
          finishedAtEpochMs: epochNow(),
          durationMs: finishedAt - startedAt,
          error: false,
          status: 200,
          matchedIndexed,
          healthyResponses: responses.filter(response => response?.source === 'TYPESENSE' && response?.degraded === false).length,
          degradedResponses: responses.filter(response => response?.degraded === true).length,
        };
      } catch (error) {
        const finishedAt = now();
        return {
          startedAt,
          finishedAt,
          startedAtEpochMs,
          finishedAtEpochMs: epochNow(),
          durationMs: finishedAt - startedAt,
          error: true,
          status: error instanceof BenchmarkHttpError ? error.status : null,
          matchedIndexed: false,
          healthyResponses: 0,
          degradedResponses: 0,
        };
      }
    }));
    measurements.push(...values);
  }
  return {
    ...summarizeMeasurements(measurements),
    measurements,
    startedAt: Math.min(...measurements.map(measurement => measurement.startedAt)),
    finishedAt: Math.max(...measurements.map(measurement => measurement.finishedAt)),
  };
}

const OVERLAP_CLOCK_ASSUMPTION = 'Benchmark host epoch timestamps and server timestamps are assumed to use the same wall clock.';

function unmeasuredJobLifetimeOverlap(reason, job = {}) {
  return {
    measured: false,
    reason,
    serverCreatedAt: typeof job.createdAt === 'string' ? job.createdAt : null,
    serverFinishedAt: typeof job.finishedAt === 'string' ? job.finishedAt : null,
    intervalIncludesQueueTime: true,
    clockAlignmentAssumption: OVERLAP_CLOCK_ASSUMPTION,
    operations: 0,
    latencyPopulation: 'successful healthy indexed matches only',
    latencySamples: 0,
    p50Ms: null,
    p95Ms: null,
    p99Ms: null,
    errors: 0,
    rateLimited: 0,
    healthyResponses: 0,
    degradedResponses: 0,
    overlapDurationMs: null,
  };
}

export function summarizeJobLifetimeOverlap(measurements, job) {
  const createdAt = Date.parse(job?.createdAt);
  const finishedAt = Date.parse(job?.finishedAt);
  if (!Number.isFinite(createdAt) || !Number.isFinite(finishedAt) || finishedAt <= createdAt) {
    return unmeasuredJobLifetimeOverlap('REBUILD job did not expose a valid createdAt..finishedAt server interval', job);
  }
  const overlapping = [];
  for (const measurement of measurements) {
    if (!Number.isFinite(measurement.startedAtEpochMs) || !Number.isFinite(measurement.finishedAtEpochMs)
        || measurement.finishedAtEpochMs <= measurement.startedAtEpochMs) continue;
    const clippedStart = Math.max(createdAt, measurement.startedAtEpochMs);
    const clippedFinish = Math.min(finishedAt, measurement.finishedAtEpochMs);
    if (clippedFinish > clippedStart) overlapping.push({ measurement, clippedStart, clippedFinish });
  }
  if (overlapping.length === 0) {
    return unmeasuredJobLifetimeOverlap('No measured operation overlapped the server createdAt..finishedAt interval', job);
  }
  const intervals = overlapping
    .map(({ clippedStart, clippedFinish }) => [clippedStart, clippedFinish])
    .sort((left, right) => left[0] - right[0] || left[1] - right[1]);
  let overlapDurationMs = 0;
  let [currentStart, currentFinish] = intervals[0];
  for (const [start, finish] of intervals.slice(1)) {
    if (start <= currentFinish) {
      currentFinish = Math.max(currentFinish, finish);
    } else {
      overlapDurationMs += currentFinish - currentStart;
      [currentStart, currentFinish] = [start, finish];
    }
  }
  overlapDurationMs += currentFinish - currentStart;
  return {
    measured: true,
    reason: null,
    serverCreatedAt: job.createdAt,
    serverFinishedAt: job.finishedAt,
    intervalIncludesQueueTime: true,
    clockAlignmentAssumption: OVERLAP_CLOCK_ASSUMPTION,
    ...summarizeMeasurements(overlapping.map(({ measurement }) => measurement)),
    overlapDurationMs,
  };
}

async function waitForIndexedTask(client, taskId, sleep, epochNow) {
  const deadline = epochNow() + 120_000;
  while (epochNow() < deadline) {
    const response = await client.get(searchPath('SBV1', 'TASK'), [200]);
    if (response?.source === 'TYPESENSE' && response?.degraded === false
        && response.hits?.some(hit => String(hit.id) === taskId && hit.entityType === 'TASK')) return;
    await sleep(Math.min(SEARCH_POLL_FLOOR_MS, Math.max(0, deadline - epochNow())));
  }
  throw new Error('Synthetic task was not delivered to the healthy Typesense index within 120 seconds');
}

async function waitForSuccessfulJob(client, jobId, sleep, epochNow) {
  const deadline = epochNow() + 180_000;
  let observationRateLimited = 0;
  while (epochNow() < deadline) {
    let job;
    try {
      job = await client.get(`/api/v1/search/jobs/${jobId}`, [200]);
    } catch (error) {
      if (!(error instanceof BenchmarkHttpError) || error.status !== 429) throw error;
      observationRateLimited += 1;
      const delay = Math.max(MANAGEMENT_POLL_FLOOR_MS, error.retryAfterMs ?? 0);
      await sleep(Math.min(delay, Math.max(0, deadline - epochNow())));
      continue;
    }
    if (['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(job.state)) {
      if (job.state !== 'SUCCEEDED') throw new Error(`REBUILD job ended in ${job.state}`);
      return { job, observationRateLimited };
    }
    await sleep(Math.min(MANAGEMENT_POLL_FLOOR_MS, Math.max(0, deadline - epochNow())));
  }
  throw new Error('REBUILD job did not finish within 180 seconds');
}

async function measureDeliveryLag(client, taskId, sleep, now, epochNow, pollingIntervalMs) {
  const marker = 'sbv1-delivery-update';
  const requestStartedAt = now();
  await client.patch(`/api/v1/tasks/${taskId}`, { title: `${syntheticText(1)} ${marker}` }, [204]);
  const patchCompletedAt = now();
  const deadline = epochNow() + 120_000;
  let polls = 0;
  while (epochNow() < deadline) {
    polls += 1;
    const response = await client.get(searchPath(marker, 'TASK'), [200]);
    if (response?.source === 'TYPESENSE' && response?.degraded === false
        && response.hits?.some(hit => String(hit.id) === taskId && hit.entityType === 'TASK')) {
      const observedAt = now();
      return {
        polls,
        pollingIntervalMs,
        patchRoundTripMs: patchCompletedAt - requestStartedAt,
        requestStartToObservedMs: observedAt - requestStartedAt,
        post204ToObservedMs: observedAt - patchCompletedAt,
        interpretation: {
          patchRoundTrip: 'PATCH request round-trip ending at HTTP 204',
          requestStartToObserved: 'PATCH request start until indexed marker observation',
          post204ToObserved: 'HTTP 204 receipt until indexed marker observation; not exact database-commit lag',
        },
      };
    }
    await sleep(Math.min(pollingIntervalMs, Math.max(0, deadline - epochNow())));
  }
  throw new Error('Committed task update was not delivered to the healthy Typesense index within 120 seconds');
}

function publicQueryMix(mix) {
  return mix.map(({ kind, query, entity }) => ({ kind, query, entity }));
}

export async function runSearchBenchmark({
  argv = process.argv.slice(2),
  environment = process.env,
  fetchImpl = globalThis.fetch,
  sleep = milliseconds => new Promise(resolveSleep => setTimeout(resolveSleep, milliseconds)),
  now = () => performance.now(),
  epochNow = () => Date.now(),
  createRequestSignal = milliseconds => AbortSignal.timeout(milliseconds),
  emit = value => process.stdout.write(value),
} = {}) {
  const configuration = validateBenchmarkConfiguration(argv, environment);
  const client = new BenchmarkClient(configuration.origin, fetchImpl, createRequestSignal, epochNow);
  await authenticate(client, configuration);
  const identifiers = await seedDataset(client, configuration.counts);
  const mix = queryMix(identifiers.tasks[0]);
  await waitForIndexedTask(client, identifiers.tasks[0], sleep, epochNow);

  let requestsPerMinute = 30;
  let registeredGenerations = null;
  if (configuration.mode === 'current') {
    const settings = await client.get('/api/v1/search/settings', [200]);
    const status = await client.get('/api/v1/search/status', [200]);
    requestsPerMinute = settings.policy.requestsPerMinute;
    registeredGenerations = status.generations.length;
    if (!status.dependency?.enabled || !status.dependency?.healthy || !status.initialized) {
      throw new Error('Current benchmark requires an initialized healthy Typesense dependency');
    }
    if (registeredGenerations >= 4) {
      throw new Error('Current benchmark fixture has no safe registered-generation capacity for REBUILD');
    }
  }
  const serverRequestsPerOperation = configuration.mode === 'baseline' ? 3 : 1;
  const spacingMs = Math.ceil((serverRequestsPerOperation * 60_000) / (requestsPerMinute * 0.8));
  const common = {
    client,
    mix,
    mode: configuration.mode,
    concurrency: configuration.concurrency,
    spacingMs,
    sleep,
    now,
    epochNow,
  };

  const cold = await measuredScenario(common);
  for (const item of mix) {
    await executeSearchOperation(client, item, configuration.mode);
    await sleep(spacingMs);
  }
  const warm = await measuredScenario(common);

  let postJobRequestWindow = null;
  let jobLifetimeOverlap = unmeasuredJobLifetimeOverlap('Baseline mode does not create an index-management job');
  let rebuild = {
    measured: false,
    reason: 'Baseline mode has no index-management endpoint',
    observationRateLimited: 0,
  };
  if (configuration.mode === 'current') {
    const receipt = await client.post('/api/v1/search/jobs', {
      requestId: randomUUID(), action: 'REBUILD', generationId: null,
    }, [202]);
    const ownedOutcomes = Promise.allSettled([
      measuredScenario(common),
      waitForSuccessfulJob(client, receipt.id, sleep, epochNow),
    ]);
    const [windowOutcome, jobOutcome] = await ownedOutcomes;
    if (jobOutcome.status === 'rejected') throw jobOutcome.reason;
    if (windowOutcome.status === 'rejected') throw windowOutcome.reason;
    postJobRequestWindow = windowOutcome.value;
    const completed = jobOutcome.value;
    jobLifetimeOverlap = summarizeJobLifetimeOverlap(postJobRequestWindow.measurements, completed.job);
    rebuild = {
      measured: true,
      state: completed.job.state,
      generationId: completed.job.generationId,
      observationRateLimited: completed.observationRateLimited,
    };
  }
  const deliveryPollingIntervalMs = Math.max(SEARCH_POLL_FLOOR_MS, spacingMs);
  const deliveryLag = await measureDeliveryLag(
    client,
    identifiers.tasks[0],
    sleep,
    now,
    epochNow,
    deliveryPollingIntervalMs,
  );

  const cleanScenario = scenario => scenario === null ? null : {
    operations: scenario.operations,
    latencyPopulation: scenario.latencyPopulation,
    latencySamples: scenario.latencySamples,
    p50Ms: scenario.p50Ms,
    p95Ms: scenario.p95Ms,
    p99Ms: scenario.p99Ms,
    errors: scenario.errors,
    rateLimited: scenario.rateLimited,
    healthyResponses: scenario.healthyResponses,
    degradedResponses: scenario.degradedResponses,
  };
  const result = {
    formatVersion: 2,
    measuredAt: new Date().toISOString(),
    mode: configuration.mode,
    seed: FIXED_SEED,
    counts: configuration.counts,
    concurrency: configuration.concurrency,
    measuredRequestsPerScenario: MEASURED_REQUESTS,
    serverRequestsPerOperation,
    queryMix: publicQueryMix(mix),
    setup: {
      cold: 'first measured batch after synthetic delivery readiness, without a search warm-up',
      warm: 'one unmeasured pass over the fixed query mix before the measured batch',
      requestsPerMinute,
      pacingFraction: 0.8,
      operationSpacingMs: spacingMs,
      registeredGenerationsBeforeRebuild: registeredGenerations,
    },
    scenarios: {
      cold: cleanScenario(cold),
      warm: cleanScenario(warm),
      postJobRequestWindow: cleanScenario(postJobRequestWindow),
      jobLifetimeOverlap,
    },
    rebuild,
    deliveryLag,
    interpretation: {
      baseline: 'Baseline mode issues three sequential entity requests per operation; when run on the same current immutable image, it is an emulated fan-out comparison, not a historical pre-feature baseline.',
      historicalOldSourceLatency: 'UNMEASURED',
      beforeAfterSpeedup: 'UNMEASURED',
      scale: 'Bounded synthetic functional/performance probe; not representative scale and not an SLO verdict.',
    },
  };
  const serialized = `${JSON.stringify(result, null, 2)}\n`;
  if (configuration.output) await writeFile(resolve(configuration.output), serialized, { encoding: 'utf8', flag: 'wx' });
  emit(serialized);
  return result;
}

const invokedPath = process.argv[1] ? resolve(process.argv[1]) : '';
if (invokedPath === fileURLToPath(import.meta.url)) {
  runSearchBenchmark()
    .then(() => undefined)
    .catch((error) => {
      process.stderr.write(`${error instanceof Error ? error.message : 'Search benchmark failed'}\n`);
      process.exitCode = 1;
    });
}
