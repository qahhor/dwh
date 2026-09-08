import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { createServer } from 'node:http';
import { resolve } from 'node:path';
import test from 'node:test';

import * as searchBenchmark from '../../scripts/search-benchmark.mjs';

const { runSearchBenchmark, validateBenchmarkConfiguration } = searchBenchmark;

const benchmarkScript = resolve('scripts/search-benchmark.mjs');
const validEnvironment = Object.freeze({
  SEARCH_BENCHMARK_ORIGIN: 'http://127.0.0.1:14208',
  SEARCH_BENCHMARK_LOGIN: 'synthetic-benchmark-admin',
  SEARCH_BENCHMARK_PASSWORD: 'Synthetic!fixture-password',
});

function runExecutable(args, environment) {
  const child = spawn(process.execPath, [benchmarkScript, ...args], {
    cwd: resolve('.'),
    env: { ...process.env, ...environment },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let stdout = '';
  let stderr = '';
  child.stdout.setEncoding('utf8').on('data', chunk => stdout += chunk);
  child.stderr.setEncoding('utf8').on('data', chunk => stderr += chunk);
  return once(child, 'exit').then(([code, signal]) => ({ code, signal, stdout, stderr }));
}

async function startControlledBenchmarkServer({
  jobResponses = [{
    status: 200,
    body: {
      id: '11111111-1111-4111-8111-111111111111',
      action: 'REBUILD',
      generationId: '22222222-2222-4222-8222-222222222222',
      state: 'SUCCEEDED',
      createdAt: '1970-01-01T00:00:00.000Z',
      finishedAt: '1970-01-01T00:16:40.000Z',
    },
  }],
  readinessMisses = 0,
  deliveryMisses = 0,
  deferPostJobSearch = false,
} = {}) {
  const state = {
    created: { tasks: 0, projects: 0, users: 0 },
    jobPosts: 0,
    jobObservations: 0,
    postJobMeasuredSearchRequests: 0,
    readinessPolls: 0,
    deliveryPolls: 0,
  };
  let nextId = 1;
  let taskId = '3';
  const jobId = '11111111-1111-4111-8111-111111111111';
  const generationId = '22222222-2222-4222-8222-222222222222';
  const server = createServer(async (request, response) => {
    const url = new URL(request.url, 'http://fixture.invalid');
    const sendJson = (status, value, headers = {}) => {
      response.writeHead(status, { 'content-type': 'application/json', ...headers });
      response.end(JSON.stringify(value));
    };
    if (request.method === 'POST' && url.pathname === '/api/v1/auth/login') {
      sendJson(200, { step: 'success', user: { id: 1, forcePasswordChange: false } }, {
        'set-cookie': ['DWH_SESSION=fixture-session; Path=/; HttpOnly', 'XSRF-TOKEN=fixture-csrf; Path=/'],
      });
      return;
    }
    if (request.method === 'POST' && url.pathname === '/api/v1/tasks/projects') {
      state.created.projects += 1;
      sendJson(201, { id: nextId++ });
      return;
    }
    if (request.method === 'POST' && url.pathname === '/api/v1/iam/users') {
      state.created.users += 1;
      sendJson(201, { id: nextId++ });
      return;
    }
    if (request.method === 'POST' && url.pathname === '/api/v1/tasks') {
      state.created.tasks += 1;
      taskId = String(nextId);
      sendJson(201, { id: nextId++ });
      return;
    }
    if (request.method === 'PATCH' && /^\/api\/v1\/tasks\/\d+$/u.test(url.pathname)) {
      response.writeHead(204).end();
      return;
    }
    if (request.method === 'GET' && url.pathname === '/api/v1/search/settings') {
      sendJson(200, {
        version: 1,
        policy: {
          globalLimit: 10, requestsPerMinute: 600, burst: 60, schemaProfile: 'MIXED',
          fields: { TASK: [], PROJECT: [], USER: [] },
        },
      });
      return;
    }
    if (request.method === 'GET' && url.pathname === '/api/v1/search/status') {
      sendJson(200, {
        dependency: { enabled: true, healthy: true }, initialized: true,
        activeProfile: 'MIXED', generations: [{ id: generationId, active: true }],
      });
      return;
    }
    if (request.method === 'POST' && url.pathname === '/api/v1/search/jobs') {
      state.jobPosts += 1;
      sendJson(202, { id: jobId, state: 'QUEUED' });
      return;
    }
    if (request.method === 'GET' && url.pathname === `/api/v1/search/jobs/${jobId}`) {
      const observation = jobResponses[Math.min(state.jobObservations, jobResponses.length - 1)];
      state.jobObservations += 1;
      sendJson(observation.status, observation.body ?? { code: 'CONTROLLED_FAILURE' }, observation.headers);
      return;
    }
    if (request.method === 'GET' && url.pathname === '/api/v1/search') {
      const query = url.searchParams.get('q');
      let visible = true;
      if (query === 'SBV1') {
        state.readinessPolls += 1;
        visible = state.readinessPolls > readinessMisses;
      } else if (query === 'sbv1-delivery-update') {
        state.deliveryPolls += 1;
        visible = state.deliveryPolls > deliveryMisses;
      } else if (state.jobPosts > 0) {
        state.postJobMeasuredSearchRequests += 1;
        if (deferPostJobSearch) await new Promise(resolveImmediate => setImmediate(resolveImmediate));
      }
      sendJson(200, {
        query, totalHits: visible ? 1 : 0, foundHits: visible ? 1 : 0, hasMore: false,
        source: 'TYPESENSE', degraded: false,
        hits: visible ? [{ id: taskId, entityType: 'TASK', title: 'synthetic match' }] : [],
      });
      return;
    }
    sendJson(404, { code: 'NOT_FOUND' });
  });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  const address = server.address();
  assert(address && typeof address === 'object');
  return {
    origin: `http://127.0.0.1:${address.port}`,
    state,
    async close() {
      server.close();
      await once(server, 'close');
    },
  };
}

test('invalid dataset input is rejected by the executable before any network request', async () => {
  let requests = 0;
  const server = createServer((_request, response) => {
    requests += 1;
    response.writeHead(500).end();
  });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  const address = server.address();
  assert(address && typeof address === 'object');

  try {
    const result = await runExecutable(['--tasks=0'], {
      ...validEnvironment,
      SEARCH_BENCHMARK_ORIGIN: `http://127.0.0.1:${address.port}`,
    });
    assert.equal(result.code, 1);
    assert.match(result.stderr, /--tasks must be an integer from 1 to 3000/u);
    assert.equal(requests, 0);
  } finally {
    server.close();
    await once(server, 'close');
  }
});

test('only a bare, credential-free loopback origin on an owned port is accepted', () => {
  const rejectedOrigins = [
    'https://search.example.invalid',
    'http://0.0.0.0:14208',
    'http://127.0.0.1:4200',
    'http://127.0.0.1:14206',
    'http://user:secret@127.0.0.1:14208',
    'http://127.0.0.1:14208/api',
    'http://127.0.0.1:14208?fixture=yes',
    'http://127.0.0.1:14208#fixture',
  ];

  for (const origin of rejectedOrigins) {
    assert.throws(
      () => validateBenchmarkConfiguration([], { ...validEnvironment, SEARCH_BENCHMARK_ORIGIN: origin }),
      /SEARCH_BENCHMARK_ORIGIN must be a bare loopback HTTP origin on an owned non-protected port/u,
      origin,
    );
  }
});

test('dataset caps and supported concurrency are enforced with exact defaults', () => {
  assert.deepEqual(validateBenchmarkConfiguration([], validEnvironment), {
    origin: 'http://127.0.0.1:14208',
    login: 'synthetic-benchmark-admin',
    password: 'Synthetic!fixture-password',
    counts: { tasks: 300, projects: 30, users: 10 },
    concurrency: 1,
    mode: 'current',
    output: null,
  });

  for (const [argument, message] of [
    ['--tasks=3001', '--tasks must be an integer from 1 to 3000'],
    ['--projects=301', '--projects must be an integer from 1 to 300'],
    ['--users=101', '--users must be an integer from 1 to 100'],
    ['--concurrency=2', '--concurrency must be 1 or 5'],
  ]) {
    assert.throws(
      () => validateBenchmarkConfiguration([argument], validEnvironment),
      error => error instanceof Error && error.message === message,
      argument,
    );
  }
});

test('missing credential errors name the key without exposing another supplied secret', () => {
  const secret = 'must-never-appear-in-an-error';
  assert.throws(
    () => validateBenchmarkConfiguration([], {
      SEARCH_BENCHMARK_ORIGIN: validEnvironment.SEARCH_BENCHMARK_ORIGIN,
      SEARCH_BENCHMARK_PASSWORD: secret,
    }),
    error => {
      assert.match(error.message, /SEARCH_BENCHMARK_LOGIN/u);
      assert.doesNotMatch(error.message, new RegExp(secret, 'u'));
      return true;
    },
  );
});

test('a controlled current run seeds fixed multilingual data and records thirty operations per scenario', async () => {
  const fixture = await startControlledBenchmarkServer({
    readinessMisses: 1,
    deliveryMisses: 1,
    jobResponses: [
      { status: 429, headers: { 'retry-after': '2' } },
      {
        status: 200,
        body: {
          id: '11111111-1111-4111-8111-111111111111',
          action: 'REBUILD',
          generationId: '22222222-2222-4222-8222-222222222222',
          state: 'SUCCEEDED',
          createdAt: '1970-01-01T00:00:00.000Z',
          finishedAt: '1970-01-01T00:16:40.000Z',
        },
      },
    ],
  });
  const emitted = [];
  const sleeps = [];
  let monotonicClock = 0;
  let epochClock = 1;

  try {
    const result = await runSearchBenchmark({
      argv: ['--tasks=1', '--projects=1', '--users=1', '--concurrency=1'],
      environment: {
        ...validEnvironment,
        SEARCH_BENCHMARK_ORIGIN: fixture.origin,
        SEARCH_BENCHMARK_PASSWORD: 'controlled-password-sentinel',
      },
      sleep: async milliseconds => sleeps.push(milliseconds),
      now: () => ++monotonicClock,
      epochNow: () => ++epochClock,
      emit: value => emitted.push(value),
    });

    assert.deepEqual(fixture.state.created, { tasks: 1, projects: 1, users: 1 });
    assert.equal(fixture.state.jobPosts, 1, 'the REBUILD mutation must never be retried');
    assert.equal(fixture.state.jobObservations, 2);
    assert(sleeps.includes(10_000), 'management observation must use its safe 10/minute-family floor');
    assert(sleeps.filter(milliseconds => milliseconds === 2_500).length >= 2,
      'readiness and delivery polling must stay within the default 30/minute Search budget');
    assert.equal(result.seed, 'smartupcms-search-benchmark-v1');
    assert.equal(result.formatVersion, 2);
    assert.deepEqual(result.counts, { tasks: 1, projects: 1, users: 1 });
    assert.equal(result.measuredRequestsPerScenario, 30);
    assert.equal(result.serverRequestsPerOperation, 1);
    assert.deepEqual(Object.keys(result.scenarios), [
      'cold', 'warm', 'postJobRequestWindow', 'jobLifetimeOverlap',
    ]);
    for (const scenario of [result.scenarios.cold, result.scenarios.warm, result.scenarios.postJobRequestWindow]) {
      assert.equal(scenario.operations, 30);
      assert.equal(scenario.latencyPopulation, 'successful healthy indexed matches only');
      assert.equal(scenario.latencySamples, 30);
      assert.equal(scenario.errors, 0);
      assert.equal(scenario.rateLimited, 0);
      assert.equal(typeof scenario.p50Ms, 'number');
      assert.equal(typeof scenario.p95Ms, 'number');
      assert.equal(typeof scenario.p99Ms, 'number');
    }
    assert.equal(result.rebuild.observationRateLimited, 1);
    assert.equal(result.scenarios.jobLifetimeOverlap.measured, true);
    assert.equal(result.scenarios.jobLifetimeOverlap.operations, 30);
    assert.equal(typeof result.scenarios.jobLifetimeOverlap.overlapDurationMs, 'number');
    assert.equal(result.scenarios.jobLifetimeOverlap.intervalIncludesQueueTime, true);
    assert.match(result.scenarios.jobLifetimeOverlap.clockAlignmentAssumption, /same wall clock/u);
    assert.deepEqual(result.interpretation, {
      baseline: 'Baseline mode issues three sequential entity requests per operation; when run on the same current immutable image, it is an emulated fan-out comparison, not a historical pre-feature baseline.',
      historicalOldSourceLatency: 'UNMEASURED',
      beforeAfterSpeedup: 'UNMEASURED',
      scale: 'Bounded synthetic functional/performance probe; not representative scale and not an SLO verdict.',
    });
    assert.deepEqual(result.deliveryLag, {
      polls: 2,
      pollingIntervalMs: 2_500,
      patchRoundTripMs: 1,
      requestStartToObservedMs: 2,
      post204ToObservedMs: 1,
      interpretation: {
        patchRoundTrip: 'PATCH request round-trip ending at HTTP 204',
        requestStartToObserved: 'PATCH request start until indexed marker observation',
        post204ToObserved: 'HTTP 204 receipt until indexed marker observation; not exact database-commit lag',
      },
    });
    assert.deepEqual(result.queryMix.map(query => query.kind), [
      'russian', 'latin', 'uzbek-modifier-apostrophe', 'uzbek-ascii-apostrophe',
      'typo', 'prefix', 'exact-id', 'no-match',
    ]);
    const serialized = `${JSON.stringify(result)}\n${emitted.join('\n')}`;
    assert.doesNotMatch(serialized, /controlled-password-sentinel/u);
    assert.doesNotMatch(serialized, /synthetic match/u);
  } finally {
    await fixture.close();
  }
});

test('baseline mode is an explicit three-sequential-request emulation with no management job', async () => {
  const fixture = await startControlledBenchmarkServer();
  try {
    const result = await runSearchBenchmark({
      argv: ['--tasks=1', '--projects=1', '--users=1', '--mode=baseline'],
      environment: { ...validEnvironment, SEARCH_BENCHMARK_ORIGIN: fixture.origin },
      sleep: async () => undefined,
      now: () => 1,
      epochNow: () => 1,
      emit: () => undefined,
    });

    assert.equal(result.mode, 'baseline');
    assert.equal(result.serverRequestsPerOperation, 3);
    assert.equal(result.scenarios.cold.operations, 30);
    assert.equal(result.scenarios.warm.operations, 30);
    assert.equal(result.scenarios.postJobRequestWindow, null);
    assert.equal(result.scenarios.jobLifetimeOverlap.measured, false);
    assert.equal(result.rebuild.measured, false);
    assert.equal(result.deliveryLag.pollingIntervalMs, 7_500);
    assert.equal(fixture.state.jobPosts, 0);
    assert.match(result.interpretation.baseline, /emulated fan-out comparison/u);
    assert.equal(result.interpretation.historicalOldSourceLatency, 'UNMEASURED');
    assert.equal(result.interpretation.beforeAfterSpeedup, 'UNMEASURED');
  } finally {
    await fixture.close();
  }
});

test('a delayed terminal job failure is owned immediately and cannot serialize a successful result', async () => {
  const fixture = await startControlledBenchmarkServer({
    deferPostJobSearch: true,
    jobResponses: [{ status: 500 }],
  });
  const emitted = [];

  try {
    await assert.rejects(
      runSearchBenchmark({
        argv: ['--tasks=1', '--projects=1', '--users=1'],
        environment: { ...validEnvironment, SEARCH_BENCHMARK_ORIGIN: fixture.origin },
        sleep: async () => undefined,
        now: () => 1,
        epochNow: () => 1,
        emit: value => emitted.push(value),
      }),
      /GET \/api\/v1\/search\/jobs\/11111111-1111-4111-8111-111111111111 returned HTTP 500/u,
    );
    assert.equal(fixture.state.jobPosts, 1);
    assert.equal(fixture.state.postJobMeasuredSearchRequests, 30,
      'the owned measurement branch must settle before the job failure escapes');
    assert.deepEqual(emitted, []);
  } finally {
    await fixture.close();
  }
});

test('a stuck fetch receives the bounded transport cancellation signal', async () => {
  let suppliedTimeout = null;
  let activeRequests = 0;
  const benchmark = runSearchBenchmark({
    argv: ['--tasks=1', '--projects=1', '--users=1'],
    environment: validEnvironment,
    fetchImpl: (_url, options) => new Promise((_resolve, reject) => {
      activeRequests += 1;
      const signal = options.signal;
      if (!signal) return;
      const rejectOnAbort = () => {
        activeRequests -= 1;
        reject(signal.reason);
      };
      if (signal.aborted) rejectOnAbort();
      else signal.addEventListener('abort', rejectOnAbort, { once: true });
    }),
    createRequestSignal: milliseconds => {
      suppliedTimeout = milliseconds;
      const controller = new AbortController();
      queueMicrotask(() => controller.abort(new Error('controlled request timeout')));
      return controller.signal;
    },
    emit: () => assert.fail('a timed-out run must not emit a result'),
  });
  let guardTimer;
  const guard = new Promise((_resolve, reject) => {
    guardTimer = setTimeout(() => reject(new Error('transport cancellation was not armed')), 100);
  });

  try {
    await assert.rejects(Promise.race([benchmark, guard]), /controlled request timeout/u);
    assert.equal(suppliedTimeout, 30_000);
    assert.equal(activeRequests, 0);
  } finally {
    clearTimeout(guardTimer);
  }
});

test('job-lifetime overlap ignores delayed observation and post-finished requests while unioning concurrency', () => {
  const measurements = [
    { startedAtEpochMs: 90, finishedAtEpochMs: 110, durationMs: 20, error: false, matchedIndexed: true, status: 200, healthyResponses: 1, degradedResponses: 0 },
    { startedAtEpochMs: 120, finishedAtEpochMs: 140, durationMs: 20, error: false, matchedIndexed: true, status: 200, healthyResponses: 1, degradedResponses: 0 },
    { startedAtEpochMs: 125, finishedAtEpochMs: 150, durationMs: 25, error: true, matchedIndexed: false, status: 429, healthyResponses: 0, degradedResponses: 0 },
    { startedAtEpochMs: 180, finishedAtEpochMs: 220, durationMs: 40, error: false, matchedIndexed: false, status: 200, healthyResponses: 0, degradedResponses: 1 },
    { startedAtEpochMs: 230, finishedAtEpochMs: 240, durationMs: 10, error: false, matchedIndexed: true, status: 200, healthyResponses: 1, degradedResponses: 0 },
  ];

  const summary = searchBenchmark.summarizeJobLifetimeOverlap(measurements, {
    createdAt: '1970-01-01T00:00:00.100Z',
    finishedAt: '1970-01-01T00:00:00.200Z',
    observedAt: '1970-01-01T00:00:01.000Z',
  });

  assert.equal(summary.measured, true);
  assert.equal(summary.operations, 4);
  assert.equal(summary.overlapDurationMs, 60);
  assert.equal(summary.latencySamples, 2);
  assert.equal(summary.p50Ms, 20);
  assert.equal(summary.p95Ms, 20);
  assert.equal(summary.errors, 1);
  assert.equal(summary.rateLimited, 1);
  assert.equal(summary.degradedResponses, 1);
});

test('job-lifetime overlap stays unmeasured for missing, invalid, or zero-overlap timing', () => {
  const measurements = [
    { startedAtEpochMs: 300, finishedAtEpochMs: 320, durationMs: 20, error: false, matchedIndexed: true, status: 200, healthyResponses: 1, degradedResponses: 0 },
  ];
  const cases = [
    {},
    { createdAt: 'invalid', finishedAt: '1970-01-01T00:00:00.200Z' },
    { createdAt: '1970-01-01T00:00:00.100Z', finishedAt: '1970-01-01T00:00:00.200Z' },
  ];

  for (const job of cases) {
    const summary = searchBenchmark.summarizeJobLifetimeOverlap(measurements, job);
    assert.equal(summary.measured, false);
    assert.equal(summary.overlapDurationMs, null);
    assert.equal(summary.p50Ms, null);
    assert.equal(summary.p95Ms, null);
    assert.equal(summary.p99Ms, null);
  }
});
