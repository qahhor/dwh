import { ApplicationRef } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ProblemDetail } from '@core/models/common.models';
import {
  SearchJobStatus,
  SearchManagementStatus,
  SearchQueryPolicy,
  SearchSettingsSnapshot,
} from '@core/models/search-management.models';
import { I18nService } from '@core/services/i18n.service';
import { PermissionService } from '@core/services/permission.service';
import { SearchManagementService } from '@core/services/search-management.service';
import { translateTest } from '@testing/i18n-test.stub';
import { SearchSettingsStore } from './search-settings.store';

const ADMIN = ['search.view', 'md.settings.view', 'md.settings.update'];

const policy: SearchQueryPolicy = {
  globalLimit: 10,
  requestsPerMinute: 120,
  burst: 20,
  schemaProfile: 'MIXED',
  fields: {
    TASK: [{ field: 'title', weight: 10, numTypos: 2, prefix: true }],
    PROJECT: [{ field: 'name', weight: 10, numTypos: 2, prefix: true }],
    USER: [{ field: 'name', weight: 10, numTypos: 2, prefix: true }],
  },
};

const status: SearchManagementStatus = {
  dependency: { enabled: true, healthy: true },
  initialized: true,
  activeProfile: 'MIXED',
  settingsDegraded: false,
  generations: [],
  budgets: {
    connectTimeoutMs: 500,
    readTimeoutMs: 1500,
    fallbackTimeoutMs: 2000,
    searchRate: { user: { perMinute: 120, capacity: 20 }, api: { perMinute: 90, capacity: 20 } },
  },
  jobs: [],
  rollbackTargets: [],
};

function job(overrides: Partial<SearchJobStatus> = {}): SearchJobStatus {
  return {
    id: 'job-1',
    action: 'CHECK',
    generationId: 'generation-1',
    state: 'RUNNING',
    processedCount: 1,
    failedCount: 0,
    createdAt: '',
    updatedAt: '',
    ...overrides,
  };
}

function problem(detail: string, status = 503): ProblemDetail {
  return { title: 'Unavailable', status, code: 'SERVICE_UNAVAILABLE', detail };
}

/** The store with its screen's collaborators; status and settings load on the first settle. */
function setup(overrides: Partial<Record<keyof SearchManagementService, unknown>> = {}) {
  const management = {
    status: vi.fn(() => of(structuredClone(status))),
    settings: vi.fn(() => of({ version: 7, policy: structuredClone(policy) } satisfies SearchSettingsSnapshot)),
    save: vi.fn(() => new Subject<SearchSettingsSnapshot>()),
    startJob: vi.fn(() => of({ id: 'job-1', state: 'QUEUED' })),
    job: vi.fn(() => new Subject<SearchJobStatus>()),
    jobs: vi.fn(() => of({ items: [], hasMore: false })),
    cancel: vi.fn(() => new Subject()),
    retry: vi.fn(() => of({ id: 'job-2', state: 'QUEUED' })),
    ...overrides,
  } as Record<string, ReturnType<typeof vi.fn>>;
  TestBed.configureTestingModule({
    providers: [
      SearchSettingsStore,
      { provide: SearchManagementService, useValue: management },
      {
        provide: PermissionService,
        useValue: { hasPermission: (form: string, action: string) => ADMIN.includes(`${form}.${action}`) },
      },
      { provide: I18nService, useValue: { translate: translateTest } },
    ],
  });
  return { store: TestBed.inject(SearchSettingsStore), management };
}

/** Runs the resource effects and waits for their answers. */
async function settle(): Promise<void> {
  TestBed.tick();
  await TestBed.inject(ApplicationRef).whenStable();
}

describe('SearchSettingsStore', () => {
  afterEach(() => vi.useRealTimers());

  it('keeps the last status on a failed refresh and closes the maintenance gate until one succeeds', async () => {
    const statusCall = vi
      .fn()
      .mockReturnValueOnce(of(structuredClone(status)))
      .mockReturnValueOnce(throwError(() => problem('Status unavailable')));
    const { store } = setup({ status: statusCall });
    await settle();
    expect(store.statusAuthorized()).toBe(true);
    expect(store.canMaintain()).toBe(true);

    // Kept means what was read, as the screen reads it on every pass.
    expect(store.status()?.activeProfile).toBe('MIXED');

    store.refreshStatus();
    await settle();

    expect(statusCall).toHaveBeenCalledTimes(2);
    expect(store.status()?.activeProfile).toBe('MIXED');
    expect(store.statusError()?.detail).toBe('Status unavailable');
    expect(store.statusAuthorized()).toBe(false);
    expect(store.canMaintain()).toBe(false);
  });

  it('lets a new refresh replace a status request still in flight', async () => {
    let unsubscribed = 0;
    const statusCall = vi
      .fn()
      .mockReturnValueOnce(of(structuredClone(status)))
      .mockReturnValueOnce(new Observable(() => () => unsubscribed++))
      .mockReturnValueOnce(of({ ...structuredClone(status), activeProfile: 'RU' }));
    const { store } = setup({ status: statusCall });
    await settle();

    store.refreshStatus();
    TestBed.tick();
    expect(store.statusLoading()).toBe(true);
    store.refreshStatus();
    await settle();

    expect(unsubscribed).toBe(1);
    expect(statusCall).toHaveBeenCalledTimes(3);
    expect(store.status()?.activeProfile).toBe('RU');
    expect(store.statusLoading()).toBe(false);
  });

  it('replaces a conflicting draft with the server policy on an explicit reload and forgets the save error', async () => {
    const settings = vi
      .fn()
      .mockReturnValueOnce(of({ version: 7, policy: structuredClone(policy) }))
      .mockReturnValueOnce(of({ version: 9, policy: { ...structuredClone(policy), globalLimit: 30 } }));
    const { store } = setup({ settings, save: vi.fn(() => throwError(() => problem('Conflict', 409))) });
    await settle();
    store.updatePolicyNumber('globalLimit', 12);
    store.save();
    expect(store.saveError()?.status).toBe(409);
    expect(store.draft()?.globalLimit).toBe(12);

    store.loadSettings();
    await settle();

    expect(store.savedSettings()?.version).toBe(9);
    expect(store.draft()?.globalLimit).toBe(30);
    expect(store.dirty()).toBe(false);
    expect(store.saveError()).toBeNull();
    expect(store.saveAttempted()).toBe(false);
  });

  it('confirms a rebuild and repeats an uncertain start with the same request identity', async () => {
    const startJob = vi
      .fn()
      .mockReturnValueOnce(throwError(() => problem('Ответ неизвестен')))
      .mockReturnValueOnce(new Subject());
    const { store } = setup({ startJob });
    await settle();

    store.requestMaintenance('REBUILD');
    expect(startJob).not.toHaveBeenCalled();
    expect(store.confirmation()).toEqual({ action: 'REBUILD' });
    store.confirmMaintenance();

    expect(startJob).toHaveBeenCalledTimes(1);
    const firstRequest = startJob.mock.calls[0][0];
    expect(firstRequest).toMatchObject({ action: 'REBUILD' });
    expect(firstRequest.requestId).toMatch(/^[0-9a-f-]{36}$/u);
    expect(firstRequest).not.toHaveProperty('generationId');
    store.retryUncertainMutation();

    expect(startJob).toHaveBeenCalledTimes(2);
    expect(startJob.mock.calls[1][0]).toEqual(firstRequest);
  });

  it('polls at once and then only six times per sustained minute', async () => {
    const poll = vi.fn(() => of(job()));
    const { store } = setup({ job: poll });
    await settle();
    vi.useFakeTimers();

    store.requestMaintenance('CHECK');
    vi.advanceTimersByTime(59_999);

    // Literal expectations pin the approved public cadence: one immediate request, then one at 10/20/30/40/50 s.
    expect(poll).toHaveBeenCalledTimes(6);
    vi.advanceTimersByTime(1);
    expect(poll).toHaveBeenCalledTimes(7);
  });

  it('never overlaps job polls, stops at a terminal state and refreshes status and history once', async () => {
    const answer = new Subject<SearchJobStatus>();
    const { store, management } = setup({ job: vi.fn(() => answer) });
    await settle();
    vi.useFakeTimers();

    store.requestMaintenance('CHECK');
    vi.advanceTimersByTime(45_000);
    expect(management['job']).toHaveBeenCalledTimes(1);

    answer.next(job({ state: 'SUCCEEDED', finishedAt: '' }));
    TestBed.tick();
    expect(store.activeOperation()).toBe(false);
    expect(management['status']).toHaveBeenCalledTimes(2);
    expect(management['jobs']).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(45_000);
    expect(management['job']).toHaveBeenCalledTimes(1);
  });

  it('blocks conflicting handlers after a job receipt while its cancel stays available', async () => {
    const { store, management } = setup();
    await settle();
    store.updatePolicyNumber('globalLimit', 12);
    vi.useFakeTimers();

    store.requestMaintenance('CHECK');
    store.save();
    store.requestMaintenance('CHECK');
    store.confirmation.set({ action: 'REBUILD' });
    store.confirmMaintenance();
    store.retryJob(job({ id: 'failed-job', state: 'FAILED' }));

    expect(store.canSave()).toBe(false);
    expect(management['save']).not.toHaveBeenCalled();
    expect(management['startJob']).toHaveBeenCalledTimes(1);
    expect(management['retry']).not.toHaveBeenCalled();
    store.cancelJob(job());
    expect(management['cancel']).toHaveBeenCalledWith('job-1');
  });

  it('restores the gate for a job running at entry and keeps it across a failed poll until polling resumes', async () => {
    const running = job({ id: 'job-reentry', action: 'REBUILD' });
    const poll = vi
      .fn()
      .mockReturnValueOnce(throwError(() => problem('Polling failed')))
      .mockReturnValueOnce(new Subject());
    const { store, management } = setup({
      status: vi.fn(() => of({ ...structuredClone(status), jobs: [running] })),
      job: poll,
    });
    vi.useFakeTimers();
    await settle();
    expect(store.activeJobId()).toBe('job-reentry');
    store.requestMaintenance('CHECK');
    expect(management['startJob']).not.toHaveBeenCalled();

    vi.advanceTimersByTime(0);
    expect(store.pollError()?.detail).toBe('Polling failed');
    expect(store.activeOperation()).toBe(true);
    store.resumePolling();
    vi.advanceTimersByTime(0);
    expect(poll).toHaveBeenCalledTimes(2);
    expect(store.pollError()).toBeNull();
  });

  it('retries an uncertain cancel for the same job while the accepted job stays active', async () => {
    const running = job({ id: 'job-cancel', action: 'REBUILD' });
    const cancel = vi
      .fn()
      .mockReturnValueOnce(throwError(() => problem('Cancel response unknown')))
      .mockReturnValueOnce(new Subject());
    const { store } = setup({ status: vi.fn(() => of({ ...structuredClone(status), jobs: [running] })), cancel });
    vi.useFakeTimers();
    await settle();
    expect(store.activeJobId()).toBe('job-cancel');

    store.cancelJob(running);
    expect(store.uncertainMutation()).toEqual({ kind: 'cancel', jobId: 'job-cancel' });
    store.retryUncertainMutation();

    expect(cancel).toHaveBeenCalledTimes(2);
    expect(cancel).toHaveBeenNthCalledWith(1, 'job-cancel');
    expect(cancel).toHaveBeenNthCalledWith(2, 'job-cancel');
  });
});
