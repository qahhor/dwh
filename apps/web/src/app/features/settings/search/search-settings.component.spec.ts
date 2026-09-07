import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ProblemDetail } from '../../../core/models/common.models';
import {
  SearchManagementStatus,
  SearchQueryPolicy,
  SearchSettingsSnapshot
} from '../../../core/models/search-management.models';
import { I18nService } from '../../../core/services/i18n.service';
import { PermissionService } from '../../../core/services/permission.service';
import { SearchManagementService } from '../../../core/services/search-management.service';
import { translateTest } from '../../../../testing/i18n-test.stub';
import { SearchSettingsComponent } from './search-settings.component';

const policy: SearchQueryPolicy = {
  globalLimit: 10,
  requestsPerMinute: 120,
  burst: 20,
  schemaProfile: 'MIXED',
  fields: {
    TASK: [
      { field: 'title', weight: 10, numTypos: 2, prefix: true },
      { field: 'description_markdown', weight: 3, numTypos: 2, prefix: true },
      { field: 'status_name', weight: 2, numTypos: 2, prefix: true },
      { field: 'project_name', weight: 2, numTypos: 2, prefix: true }
    ],
    PROJECT: [
      { field: 'name', weight: 10, numTypos: 2, prefix: true },
      { field: 'description', weight: 3, numTypos: 2, prefix: true }
    ],
    USER: [
      { field: 'name', weight: 10, numTypos: 2, prefix: true },
      { field: 'login', weight: 8, numTypos: 0, prefix: true },
      { field: 'email', weight: 6, numTypos: 0, prefix: true },
      { field: 'phone', weight: 6, numTypos: 0, prefix: true }
    ]
  }
};

const status: SearchManagementStatus = {
  dependency: {
    enabled: true,
    healthy: true,
    version: '27.1.0',
    installationDiskUsedBytes: 2048,
    installationDiskTotalBytes: 8192
  },
  initialized: true,
  activeProfile: 'MIXED',
  configuredProfile: 'MIXED',
  rebuildRequired: false,
  settingsDegraded: false,
  lastSuccessfulReconciliation: '2026-09-07T12:00:00Z',
  generations: [{
    id: 'generation-1', state: 'ACTIVE', active: true, registeredProfile: 'MIXED',
    documentCount: 14, entityDocumentCounts: { TASK: 8, PROJECT: 4, USER: 2 },
    storageBytes: 1024, schemaMatches: true, pendingDeliveries: 0, failedDeliveries: 0,
    queueLagSeconds: 0, createdAt: '2026-09-07T11:00:00Z'
  }],
  budgets: {
    connectTimeoutMs: 500,
    readTimeoutMs: 1500,
    fallbackTimeoutMs: 2000,
    searchRate: { user: { perMinute: 120, capacity: 20 }, api: { perMinute: 90, capacity: 20 } }
  },
  jobs: [],
  rollbackTargets: []
};

function clonePolicy(value: SearchQueryPolicy): SearchQueryPolicy {
  return structuredClone(value);
}

describe('SearchSettingsComponent', () => {
  async function createFixture(
    permissions: string[],
    overrides: Partial<Record<keyof SearchManagementService, unknown>> = {}
  ): Promise<{ fixture: ComponentFixture<SearchSettingsComponent>; management: Record<string, ReturnType<typeof vi.fn>> }> {
    const management = {
      status: vi.fn(() => of(structuredClone(status))),
      settings: vi.fn(() => of({ version: 7, policy: clonePolicy(policy) } satisfies SearchSettingsSnapshot)),
      save: vi.fn(() => of({ version: 8, policy: clonePolicy(policy) } satisfies SearchSettingsSnapshot)),
      preview: vi.fn(() => of({ result: { query: '', totalHits: 0, hits: [], foundHits: 0, hasMore: false, source: 'TYPESENSE', degraded: false }, activeProfile: 'MIXED' })),
      startJob: vi.fn(() => of({ id: 'job-1', state: 'QUEUED' })),
      job: vi.fn(() => of({ id: 'job-1', action: 'CHECK', generationId: 'generation-1', state: 'SUCCEEDED', processedCount: 14, failedCount: 0, createdAt: '', updatedAt: '' })),
      jobs: vi.fn(() => of({ items: [], hasMore: false })),
      cancel: vi.fn(() => of({ id: 'job-1', state: 'CANCELLED' })),
      retry: vi.fn(() => of({ id: 'job-2', state: 'QUEUED' })),
      ...overrides
    } as Record<string, ReturnType<typeof vi.fn>>;
    const available = new Set(permissions);
    await TestBed.configureTestingModule({
      imports: [SearchSettingsComponent],
      providers: [
        { provide: SearchManagementService, useValue: management },
        { provide: PermissionService, useValue: { hasPermission: (form: string, action: string) => available.has(`${form}.${action}`) } },
        { provide: I18nService, useValue: { currentLang: signal('ru'), translate: translateTest } }
      ]
    }).compileComponents();
    const fixture = TestBed.createComponent(SearchSettingsComponent);
    fixture.detectChanges();
    return { fixture, management };
  }

  function setNumber(fixture: ComponentFixture<SearchSettingsComponent>, selector: string, value: string): HTMLInputElement {
    const input = fixture.nativeElement.querySelector(selector) as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    return input;
  }

  it('does not call management APIs or render controls when search permission is missing', async () => {
    const { fixture, management } = await createFixture([]);

    expect(management['status']).not.toHaveBeenCalled();
    expect(management['settings']).not.toHaveBeenCalled();
    expect(fixture.nativeElement.querySelector('[data-state="search-access-unavailable"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('button[data-action="save-search-settings"]')).toBeNull();
  });

  it('shows server-confirmed status without configuration or mutation controls to a read-only search administrator', async () => {
    const { fixture, management } = await createFixture(['platform.search.view']);

    expect(management['status']).toHaveBeenCalledTimes(1);
    expect(management['settings']).not.toHaveBeenCalled();
    expect(fixture.nativeElement.querySelector('[data-status="healthy"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('[data-readiness="ready"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('[data-effective-api="90"]')).not.toBeNull();
    expect(fixture.nativeElement.textContent).toContain('27.1.0');
    expect(fixture.nativeElement.textContent).toContain('1500');
    expect(fixture.nativeElement.querySelector('[data-state="configuration-not-authorized"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('button[data-action="start-rebuild"]')).toBeNull();
  });

  it('keeps maintenance authorization independent when configuration read fails and invents no baseline', async () => {
    const unavailable: ProblemDetail = { title: 'Unavailable', status: 503, code: 'SERVICE_UNAVAILABLE', detail: 'Нет конфигурации' };
    const { fixture, management } = await createFixture([
      'platform.search.view', 'platform.settings.view', 'platform.settings.update'
    ], { settings: vi.fn(() => throwError(() => unavailable)) });

    expect(management['settings']).toHaveBeenCalledTimes(1);
    expect(fixture.nativeElement.querySelector('[data-state="configuration-unavailable"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('[data-state="policy-clean"]')).toBeNull();
    expect(fixture.nativeElement.querySelector('button[data-action="save-search-settings"]')).toBeNull();
    expect(fixture.nativeElement.querySelector('button[data-action="start-rebuild"]')).not.toBeNull();
  });

  it('blocks an invalid policy before issuing a save request', async () => {
    const { fixture, management } = await createFixture([
      'platform.search.view', 'platform.settings.view', 'platform.settings.update'
    ]);
    setNumber(fixture, '#search-global-limit', '0');

    (fixture.nativeElement.querySelector('button[data-action="save-search-settings"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(management['save']).not.toHaveBeenCalled();
    expect(fixture.nativeElement.querySelector('[data-state="policy-invalid"][role="alert"]')).not.toBeNull();
  });

  it('allows only one save while pending and disables the real template button', async () => {
    const pending = new Subject<SearchSettingsSnapshot>();
    const { fixture, management } = await createFixture([
      'platform.search.view', 'platform.settings.view', 'platform.settings.update'
    ], { save: vi.fn(() => pending) });
    setNumber(fixture, '#search-global-limit', '12');
    const saveButton = fixture.nativeElement.querySelector('button[data-action="save-search-settings"]') as HTMLButtonElement;

    saveButton.click();
    saveButton.click();
    fixture.detectChanges();

    expect(management['save']).toHaveBeenCalledTimes(1);
    expect(management['save']).toHaveBeenCalledWith(expect.objectContaining({
      version: 7,
      policy: expect.objectContaining({ globalLimit: 12 })
    }));
    expect(saveButton.disabled).toBe(true);
  });

  it('preserves the edited draft when save fails', async () => {
    const failure: ProblemDetail = { title: 'Unavailable', status: 503, code: 'SERVICE_UNAVAILABLE', detail: 'Временно недоступно' };
    const { fixture } = await createFixture([
      'platform.search.view', 'platform.settings.view', 'platform.settings.update'
    ], { save: vi.fn(() => throwError(() => failure)) });
    const limit = setNumber(fixture, '#search-global-limit', '17');

    (fixture.nativeElement.querySelector('button[data-action="save-search-settings"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(limit.value).toBe('17');
    expect(fixture.nativeElement.querySelector('[data-state="save-error"]').textContent).toContain('Временно недоступно');
  });

  it('preserves a conflicting draft and offers an explicit server reload', async () => {
    const conflict: ProblemDetail = { title: 'Conflict', status: 409, code: 'CONFLICT', detail: 'Версия уже изменена' };
    const reload = new Subject<SearchSettingsSnapshot>();
    const settings = vi.fn()
      .mockReturnValueOnce(of({ version: 7, policy: clonePolicy(policy) }))
      .mockReturnValueOnce(reload);
    const { fixture } = await createFixture([
      'platform.search.view', 'platform.settings.view', 'platform.settings.update'
    ], { settings, save: vi.fn(() => throwError(() => conflict)) });
    const limit = setNumber(fixture, '#search-global-limit', '25');

    (fixture.nativeElement.querySelector('button[data-action="save-search-settings"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(limit.value).toBe('25');
    const reloadButton = fixture.nativeElement.querySelector('button[data-action="reload-search-settings"]') as HTMLButtonElement;
    expect(reloadButton).not.toBeNull();
    reloadButton.click();
    expect(settings).toHaveBeenCalledTimes(2);
  });

  it('uses a successful save as the clean baseline while marking a schema change as rebuild-required', async () => {
    const saved = clonePolicy(policy);
    saved.schemaProfile = 'RU';
    const { fixture } = await createFixture([
      'platform.search.view', 'platform.settings.view', 'platform.settings.update'
    ], { save: vi.fn(() => of({ version: 8, policy: saved })) });
    const profile = fixture.nativeElement.querySelector('#search-schema-profile') as HTMLSelectElement;
    profile.value = 'RU';
    profile.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    (fixture.nativeElement.querySelector('button[data-action="save-search-settings"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[data-state="policy-clean"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('[data-state="rebuild-required"]')).not.toBeNull();
  });

  it('does not replace a dirty policy when status refresh completes in the background', async () => {
    const laterStatus = new Subject<SearchManagementStatus>();
    const statusCall = vi.fn()
      .mockReturnValueOnce(of(structuredClone(status)))
      .mockReturnValueOnce(laterStatus);
    const { fixture, management } = await createFixture([
      'platform.search.view', 'platform.settings.view', 'platform.settings.update'
    ], { status: statusCall });
    const limit = setNumber(fixture, '#search-global-limit', '19');

    (fixture.nativeElement.querySelector('button[data-action="refresh-search-status"]') as HTMLButtonElement).click();
    laterStatus.next({ ...structuredClone(status), configuredProfile: 'RU', rebuildRequired: true });
    fixture.detectChanges();

    expect(statusCall).toHaveBeenCalledTimes(2);
    expect(management['jobs']).toHaveBeenCalledTimes(2);
    expect(limit.value).toBe('19');
  });

  it('cancels a replaced preview and renders snippet text as escaped text', async () => {
    const cancelled: string[] = [];
    const preview = vi.fn((request: { q: string }) => new Observable(subscriber => {
      if (request.q === 'second') subscriber.next({
        result: {
          query: 'second', totalHits: 1, foundHits: 1, hasMore: false, source: 'TYPESENSE', degraded: false,
          hits: [{ entityType: 'TASK', id: '1', title: 'Safe', description: '<img src=x onerror=alert(1)>', targetUrl: '/tasks/items/1' }]
        },
        activeProfile: 'MIXED'
      });
      return () => cancelled.push(request.q);
    }));
    const { fixture } = await createFixture([
      'platform.search.view', 'platform.settings.view', 'platform.settings.update'
    ], { preview });
    const query = fixture.nativeElement.querySelector('#search-preview-query') as HTMLInputElement;
    query.value = 'first'; query.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('button[data-action="preview-search-settings"]') as HTMLButtonElement).click();
    query.value = 'second'; query.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('button[data-action="preview-search-settings"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(cancelled).toContain('first');
    expect(fixture.nativeElement.querySelector('.preview-hit img')).toBeNull();
    expect(fixture.nativeElement.querySelector('.preview-hit').textContent).toContain('<img src=x onerror=alert(1)>');
    expect(fixture.nativeElement.querySelector('[data-active-profile="MIXED"]')).not.toBeNull();
  });

  it('confirms rebuild and reuses its request identity after an uncertain failure', async () => {
    const unavailable: ProblemDetail = { title: 'Unavailable', status: 503, code: 'SERVICE_UNAVAILABLE', detail: 'Ответ неизвестен' };
    const startJob = vi.fn()
      .mockReturnValueOnce(throwError(() => unavailable))
      .mockReturnValueOnce(new Subject());
    const { fixture } = await createFixture([
      'platform.search.view', 'platform.settings.view', 'platform.settings.update'
    ], { startJob });

    (fixture.nativeElement.querySelector('button[data-action="start-rebuild"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(startJob).not.toHaveBeenCalled();
    const confirm = fixture.nativeElement.querySelector('button[data-action="confirm-search-maintenance"]') as HTMLButtonElement;
    expect(confirm).not.toBeNull();
    confirm.click();
    fixture.detectChanges();

    expect(startJob).toHaveBeenCalledTimes(1);
    const firstRequest = startJob.mock.calls[0][0];
    expect(firstRequest).toMatchObject({ action: 'REBUILD' });
    expect(firstRequest.requestId).toMatch(/^[0-9a-f-]{36}$/u);
    expect(firstRequest).not.toHaveProperty('generationId');
    const retry = fixture.nativeElement.querySelector('button[data-action="retry-uncertain-mutation"]') as HTMLButtonElement;
    expect(retry).not.toBeNull();
    retry.click();

    expect(startJob).toHaveBeenCalledTimes(2);
    expect(startJob.mock.calls[1][0]).toEqual(firstRequest);
    fixture.destroy();
  });

  it('uses only returned rollback targets and blocks rebuild at the four-generation guard', async () => {
    const full = structuredClone(status);
    full.rollbackTargets = [{ id: 'retained-1', schemaProfile: 'MIXED', lastVerifiedAt: null }];
    full.generations = Array.from({ length: 4 }, (_, index) => ({
      ...structuredClone(status.generations[0]), id: `generation-${index + 1}`, active: index === 0,
      state: index === 0 ? 'ACTIVE' : 'RETAINED', storageBytes: index === 3 ? null : 1024
    }));
    const startJob = vi.fn(() => new Subject());
    const { fixture } = await createFixture([
      'platform.search.view', 'platform.settings.view', 'platform.settings.update'
    ], { status: vi.fn(() => of(full)), startJob });

    const rebuild = fixture.nativeElement.querySelector('button[data-action="start-rebuild"]') as HTMLButtonElement;
    expect(rebuild.disabled).toBe(true);
    expect(fixture.nativeElement.querySelector('[data-state="generation-capacity"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('button[data-action*="delete"], input[name*="generation"]')).toBeNull();
    const rollback = fixture.nativeElement.querySelector('button[data-generation-id="retained-1"]') as HTMLButtonElement;
    rollback.click(); fixture.detectChanges();
    (fixture.nativeElement.querySelector('button[data-action="confirm-search-maintenance"]') as HTMLButtonElement).click();

    expect(startJob).toHaveBeenCalledWith(expect.objectContaining({ action: 'ROLLBACK', generationId: 'retained-1' }));
    fixture.destroy();
  });

  it('never overlaps job polls, stops at terminal state and refreshes status and history once', async () => {
    vi.useFakeTimers();
    try {
      const poll = new Subject<any>();
      const statusCall = vi.fn(() => of(structuredClone(status)));
      const historyCall = vi.fn(() => of({ items: [], hasMore: false }));
      const job = vi.fn(() => poll);
      const { fixture } = await createFixture([
        'platform.search.view', 'platform.settings.update'
      ], {
        status: statusCall,
        jobs: historyCall,
        startJob: vi.fn(() => of({ id: 'job-1', state: 'QUEUED' })),
        job
      });

      (fixture.nativeElement.querySelector('button[data-action="start-check"]') as HTMLButtonElement).click();
      vi.advanceTimersByTime(4500);
      expect(job).toHaveBeenCalledTimes(1);
      poll.next({
        id: 'job-1', action: 'CHECK', generationId: 'generation-1', state: 'SUCCEEDED',
        processedCount: 14, failedCount: 0, createdAt: '', updatedAt: '', finishedAt: ''
      });
      expect(statusCall).toHaveBeenCalledTimes(2);
      expect(historyCall).toHaveBeenCalledTimes(2);
      vi.advanceTimersByTime(4500);
      expect(job).toHaveBeenCalledTimes(1);
      fixture.destroy();
    } finally {
      vi.useRealTimers();
    }
  });

  it('loads the next bounded history page with the opaque server cursor', async () => {
    const first = {
      id: 'job-1', action: 'CHECK' as const, generationId: 'generation-1', state: 'SUCCEEDED' as const,
      processedCount: 14, failedCount: 0, createdAt: '2026-09-07T12:00:00Z', updatedAt: '2026-09-07T12:01:00Z'
    };
    const second = { ...first, id: 'job-2', createdAt: '2026-09-06T12:00:00Z' };
    const jobs = vi.fn()
      .mockReturnValueOnce(of({ items: [first], nextCursor: 'opaque+/=', hasMore: true }))
      .mockReturnValueOnce(of({ items: [second], hasMore: false }));
    const { fixture } = await createFixture(['platform.search.view'], { jobs });

    const more = fixture.nativeElement.querySelector('button[data-action="load-more-search-jobs"]') as HTMLButtonElement;
    expect(more).not.toBeNull();
    more.click(); fixture.detectChanges();

    expect(jobs).toHaveBeenNthCalledWith(2, 20, 'opaque+/=');
    expect(fixture.nativeElement.textContent).toContain('job-1');
    expect(fixture.nativeElement.textContent).toContain('job-2');
    expect(fixture.nativeElement.querySelector('button[data-action="load-more-search-jobs"]')).toBeNull();
  });

  it('stops an active job poll on destruction without asking the server to cancel work', async () => {
    vi.useFakeTimers();
    try {
      let pollUnsubscribed = 0;
      const job = vi.fn(() => new Observable(() => () => pollUnsubscribed++));
      const cancel = vi.fn(() => of({ id: 'job-1', state: 'CANCELLED' }));
      const { fixture } = await createFixture([
        'platform.search.view', 'platform.settings.update'
      ], { startJob: vi.fn(() => of({ id: 'job-1', state: 'QUEUED' })), job, cancel });

      (fixture.nativeElement.querySelector('button[data-action="start-check"]') as HTMLButtonElement).click();
      vi.advanceTimersByTime(0);
      expect(job).toHaveBeenCalledTimes(1);
      fixture.destroy();
      vi.advanceTimersByTime(4500);

      expect(pollUnsubscribed).toBe(1);
      expect(job).toHaveBeenCalledTimes(1);
      expect(cancel).not.toHaveBeenCalled();
    } finally {
      vi.useRealTimers();
    }
  });

  it('maps an unknown historical job error to the generic safe message', async () => {
    const failed = {
      id: 'failed-job', action: 'REBUILD' as const, generationId: 'generation-2', state: 'FAILED' as const,
      processedCount: 3, failedCount: 1, errorCode: 'RAW_DOWNSTREAM_SECRET',
      createdAt: '2026-09-07T12:00:00Z', updatedAt: '2026-09-07T12:01:00Z'
    };
    const failedStatus = { ...structuredClone(status), jobs: [failed] };
    const { fixture } = await createFixture(['platform.search.view'], {
      status: vi.fn(() => of(failedStatus)),
      jobs: vi.fn(() => of({ items: [], hasMore: false }))
    });

    const rendered = fixture.nativeElement.querySelector('[data-job-error="failed-job"]') as HTMLElement;
    expect(rendered).not.toBeNull();
    expect(rendered.textContent).toContain('Задание завершилось с безопасно скрытой ошибкой');
    expect(rendered.textContent).not.toContain('RAW_DOWNSTREAM_SECRET');
  });

  it('resolves every visible search-tab key through the packaged Russian fallback', async () => {
    const { fixture } = await createFixture([
      'platform.search.view', 'platform.settings.view', 'platform.settings.update'
    ]);

    expect(fixture.nativeElement.textContent).not.toContain('settings.search.');
  });

  it('disables maintenance mutations while a policy save is in flight', async () => {
    const pending = new Subject<SearchSettingsSnapshot>();
    const { fixture, management } = await createFixture([
      'platform.search.view', 'platform.settings.view', 'platform.settings.update'
    ], { save: vi.fn(() => pending) });
    setNumber(fixture, '#search-global-limit', '12');

    (fixture.nativeElement.querySelector('button[data-action="save-search-settings"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect((fixture.nativeElement.querySelector('button[data-action="start-check"]') as HTMLButtonElement).disabled).toBe(true);
    expect((fixture.nativeElement.querySelector('button[data-action="start-rebuild"]') as HTMLButtonElement).disabled).toBe(true);
    (fixture.nativeElement.querySelector('button[data-action="start-check"]') as HTMLButtonElement).click();
    expect(management['startJob']).not.toHaveBeenCalled();
  });
});
