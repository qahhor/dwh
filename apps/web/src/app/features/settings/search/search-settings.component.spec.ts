import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
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
import { SearchSettingsComponent } from './search-settings.component';
import { SMTSelectComponent } from '@shared/ui-kit/components/forms/select';
import { inScreen } from '@testing/in-screen';

// The store's own rules (polling cadence, handler guards, refresh replacement) are pinned in
// search-settings.store.spec.ts; this spec pins what the screen shows and which controls call what.

type Fixture = ComponentFixture<SearchSettingsComponent>;
type Overrides = Partial<Record<keyof SearchManagementService, unknown>>;

const VIEW = ['platform.search.view'];
const READ = [...VIEW, 'platform.settings.view'];
const ADMIN = [...READ, 'platform.settings.update'];

// Hand-copied from the strict backend JSON contract. Intentionally does not
// derive field names from the frontend model or production helpers.
const canonicalBackendSettings = {
  version: 7,
  policy: {
    globalLimit: 10,
    requestsPerMinute: 120,
    burst: 20,
    schemaProfile: 'MIXED',
    fields: {
      TASK: [
        { field: 'title', weight: 10, numTypos: 2, prefix: true },
        { field: 'description_markdown', weight: 3, numTypos: 2, prefix: true },
        { field: 'status_name', weight: 2, numTypos: 2, prefix: true },
        { field: 'project_name', weight: 2, numTypos: 2, prefix: true },
      ],
      PROJECT: [
        { field: 'name', weight: 10, numTypos: 2, prefix: true },
        { field: 'description', weight: 3, numTypos: 2, prefix: true },
      ],
      USER: [
        { field: 'name', weight: 10, numTypos: 2, prefix: true },
        { field: 'login', weight: 8, numTypos: 0, prefix: true },
        { field: 'email', weight: 6, numTypos: 0, prefix: true },
        { field: 'phone', weight: 6, numTypos: 0, prefix: true },
      ],
    },
  },
} as unknown as SearchSettingsSnapshot;

const policy: SearchQueryPolicy = canonicalBackendSettings.policy;

const status: SearchManagementStatus = {
  dependency: {
    enabled: true,
    healthy: true,
    version: '27.1.0',
    installationDiskUsedBytes: 2048,
    installationDiskTotalBytes: 8192,
  },
  initialized: true,
  activeProfile: 'MIXED',
  configuredProfile: 'MIXED',
  rebuildRequired: false,
  settingsDegraded: false,
  lastSuccessfulReconciliation: '2026-09-07T12:00:00Z',
  generations: [
    {
      id: 'generation-1',
      state: 'ACTIVE',
      active: true,
      registeredProfile: 'MIXED',
      documentCount: 14,
      entityDocumentCounts: { TASK: 8, PROJECT: 4, USER: 2 },
      storageBytes: 1024,
      schemaMatches: true,
      pendingDeliveries: 0,
      failedDeliveries: 0,
      queueLagSeconds: 0,
      createdAt: '2026-09-07T11:00:00Z',
    },
  ],
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
  const base = { id: 'job-1', generationId: 'generation-1', processedCount: 14, failedCount: 0 };
  const times = { createdAt: '2026-09-07T12:00:00Z', updatedAt: '2026-09-07T12:01:00Z' };
  return { ...base, ...times, action: 'CHECK', state: 'SUCCEEDED', ...overrides };
}

function problem(detail: string, status = 503): ProblemDetail {
  return { title: 'Problem', status, code: status === 409 ? 'CONFLICT' : 'SERVICE_UNAVAILABLE', detail };
}

function previewAnswer(query = '', hits: object[] = []) {
  const result = { query, hits, totalHits: hits.length, foundHits: hits.length, hasMore: false, source: 'TYPESENSE' };
  return { result: { ...result, degraded: false }, activeProfile: 'MIXED' };
}

async function createFixture(permissions: string[], overrides: Overrides = {}) {
  const management = {
    status: vi.fn(() => of(structuredClone(status))),
    settings: vi.fn(() => of({ version: 7, policy: structuredClone(policy) } satisfies SearchSettingsSnapshot)),
    save: vi.fn(() => of({ version: 8, policy: structuredClone(policy) } satisfies SearchSettingsSnapshot)),
    preview: vi.fn(() => of(previewAnswer())),
    startJob: vi.fn(() => of({ id: 'job-1', state: 'QUEUED' })),
    job: vi.fn(() => of(job())),
    jobs: vi.fn(() => of({ items: [], hasMore: false })),
    cancel: vi.fn(() => of({ id: 'job-1', state: 'CANCELLED' })),
    retry: vi.fn(() => of({ id: 'job-2', state: 'QUEUED' })),
    ...overrides,
  } as Record<string, ReturnType<typeof vi.fn>>;
  const hasPermission = (form: string, action: string) => permissions.includes(`${form}.${action}`);
  await TestBed.configureTestingModule({
    imports: [SearchSettingsComponent],
    providers: [
      { provide: SearchManagementService, useValue: management },
      { provide: PermissionService, useValue: { hasPermission } },
      { provide: I18nService, useValue: { currentLang: signal('ru'), translate: translateTest } },
    ],
  }).compileComponents();
  const fixture = TestBed.createComponent(SearchSettingsComponent);
  fixture.detectChanges();
  return { fixture, management };
}

const find = (fixture: Fixture, selector: string) => inScreen(fixture.nativeElement).querySelector(selector);
const button = (fixture: Fixture, action: string) =>
  find(fixture, `button[data-action="${action}"]`) as HTMLButtonElement | null;

/** Clicks and redraws, as a person's click does. */
function click(fixture: Fixture, action: string): void {
  button(fixture, action)!.click();
  fixture.detectChanges();
}

/** The smt-select whose trigger has the given id. */
function picker(fixture: Fixture, triggerId: string): SMTSelectComponent<string> {
  return fixture.debugElement
    .queryAll(By.directive(SMTSelectComponent))
    .find((debug) => debug.nativeElement.querySelector(`#${triggerId}`))!
    .componentInstance as SMTSelectComponent<string>;
}

function type(fixture: Fixture, selector: string, value: string): HTMLInputElement {
  const input = find(fixture, selector) as HTMLInputElement;
  input.value = value;
  input.dispatchEvent(new Event('input'));
  fixture.detectChanges();
  return input;
}

function preview(fixture: Fixture, query: string): void {
  type(fixture, '#search-preview-query', query);
  button(fixture, 'preview-search-settings')!.click();
}

describe('SearchSettingsComponent', () => {
  afterEach(() => vi.useRealTimers());

  it('does not call management APIs or render controls when search permission is missing', async () => {
    const { fixture, management } = await createFixture([]);

    expect(management['status']).not.toHaveBeenCalled();
    expect(management['settings']).not.toHaveBeenCalled();
    expect(find(fixture, '[data-state="search-access-unavailable"]')).not.toBeNull();
    expect(button(fixture, 'save-search-settings')).toBeNull();
  });

  it('shows server-confirmed status without configuration or mutation controls to a read-only search administrator', async () => {
    const { fixture, management } = await createFixture(VIEW);

    expect(management['status']).toHaveBeenCalledTimes(1);
    expect(management['settings']).not.toHaveBeenCalled();
    expect(find(fixture, '[data-status="healthy"]')).not.toBeNull();
    expect(find(fixture, '[data-readiness="ready"]')).not.toBeNull();
    expect(find(fixture, '[data-effective-api="90"]')).not.toBeNull();
    expect(fixture.nativeElement.textContent).toContain('27.1.0');
    expect(fixture.nativeElement.textContent).toContain('1500');
    expect(find(fixture, '[data-state="configuration-not-authorized"]')).not.toBeNull();
    expect(button(fixture, 'start-rebuild')).toBeNull();
  });

  it('lets a server-confirmed search administrator preview the current policy, one entity type or all of them', async () => {
    const { fixture, management } = await createFixture(VIEW);

    expect(find(fixture, '#search-preview-query')).not.toBeNull();
    preview(fixture, 'current policy');
    expect(management['settings']).not.toHaveBeenCalled();
    expect(management['preview']).toHaveBeenCalledWith({ q: 'current policy' });
    expect(button(fixture, 'save-search-settings')).toBeNull();

    type(fixture, '#search-preview-query', 'report');
    expect(find(fixture, 'label[for="search-preview-entity"]')).not.toBeNull();
    const entity = picker(fixture, 'search-preview-entity');
    expect(entity.options().map((option) => option.id)).toEqual(['TASK', 'PROJECT', 'USER']);
    entity.pick(entity.options().find((option) => option.id === 'PROJECT')!);
    fixture.detectChanges();
    button(fixture, 'preview-search-settings')!.click();
    expect(management['preview']).toHaveBeenLastCalledWith({ q: 'report', entity: 'PROJECT' });

    entity.pickNone();
    fixture.detectChanges();
    expect(fixture.componentInstance.previewEntity).toBe('');
  });

  it('previews the readable unsaved policy but hides Save without update permission', async () => {
    const { fixture, management } = await createFixture(READ);
    type(fixture, '#search-global-limit', '13');
    preview(fixture, 'unsaved policy');

    expect(management['preview']).toHaveBeenCalledWith(
      expect.objectContaining({ q: 'unsaved policy', policy: expect.objectContaining({ globalLimit: 13 }) }),
    );
    expect(button(fixture, 'save-search-settings')).toBeNull();
  });

  it('keeps maintenance authorization independent when configuration read fails and invents no baseline', async () => {
    const { fixture, management } = await createFixture(ADMIN, {
      settings: vi.fn(() => throwError(() => problem('Нет конфигурации'))),
    });

    expect(management['settings']).toHaveBeenCalledTimes(1);
    expect(find(fixture, '[data-state="configuration-unavailable"]')).not.toBeNull();
    expect(find(fixture, '[data-state="policy-clean"]')).toBeNull();
    expect(button(fixture, 'save-search-settings')).toBeNull();
    expect(button(fixture, 'start-rebuild')).not.toBeNull();
  });

  it('blocks an invalid policy before issuing a save request', async () => {
    const { fixture, management } = await createFixture(ADMIN);
    type(fixture, '#search-global-limit', '0');

    click(fixture, 'save-search-settings');

    expect(management['save']).not.toHaveBeenCalled();
    expect(find(fixture, '[data-state="policy-invalid"][role="alert"]')).not.toBeNull();
  });

  it('allows only one save while pending and disables the real save and maintenance buttons', async () => {
    const { fixture, management } = await createFixture(ADMIN, {
      save: vi.fn(() => new Subject<SearchSettingsSnapshot>()),
    });
    type(fixture, '#search-global-limit', '12');
    const saveButton = button(fixture, 'save-search-settings')!;

    saveButton.click();
    saveButton.click();
    fixture.detectChanges();

    expect(management['save']).toHaveBeenCalledTimes(1);
    expect(management['save']).toHaveBeenCalledWith(
      expect.objectContaining({ version: 7, policy: expect.objectContaining({ globalLimit: 12 }) }),
    );
    expect(saveButton.disabled).toBe(true);
    expect(button(fixture, 'start-check')!.disabled).toBe(true);
    expect(button(fixture, 'start-rebuild')!.disabled).toBe(true);
    button(fixture, 'start-check')!.click();
    expect(management['startJob']).not.toHaveBeenCalled();
  });

  it('preserves a conflicting draft and offers an explicit server reload', async () => {
    const settings = vi
      .fn()
      .mockReturnValueOnce(of({ version: 7, policy: structuredClone(policy) }))
      .mockReturnValueOnce(new Subject<SearchSettingsSnapshot>());
    const { fixture } = await createFixture(ADMIN, {
      settings,
      save: vi.fn(() => throwError(() => problem('Версия уже изменена', 409))),
    });
    const limit = type(fixture, '#search-global-limit', '25');

    click(fixture, 'save-search-settings');

    expect(limit.value).toBe('25');
    expect(button(fixture, 'reload-search-settings')).not.toBeNull();
    click(fixture, 'reload-search-settings');
    expect(settings).toHaveBeenCalledTimes(2);
  });

  it('uses a successful save as the clean baseline while marking a schema change as rebuild-required', async () => {
    const saved = structuredClone(policy);
    saved.schemaProfile = 'RU';
    const { fixture } = await createFixture(ADMIN, { save: vi.fn(() => of({ version: 8, policy: saved })) });
    const profile = picker(fixture, 'search-schema-profile');
    expect(profile.options().map((option) => option.id)).toEqual(['MIXED', 'RU']);
    profile.pick(profile.options().find((option) => option.id === 'RU')!);
    fixture.detectChanges();

    click(fixture, 'save-search-settings');

    expect(find(fixture, '[data-state="policy-clean"]')).not.toBeNull();
    expect(find(fixture, '[data-state="rebuild-required"]')).not.toBeNull();
  });

  it('does not replace a dirty policy when status refresh completes in the background', async () => {
    const laterStatus = new Subject<SearchManagementStatus>();
    const statusCall = vi
      .fn()
      .mockReturnValueOnce(of(structuredClone(status)))
      .mockReturnValueOnce(laterStatus);
    const { fixture, management } = await createFixture(ADMIN, { status: statusCall });
    const limit = type(fixture, '#search-global-limit', '19');

    click(fixture, 'refresh-search-status');
    laterStatus.next({ ...structuredClone(status), configuredProfile: 'RU', rebuildRequired: true });
    await fixture.whenStable();
    fixture.detectChanges();

    expect(statusCall).toHaveBeenCalledTimes(2);
    expect(management['jobs']).toHaveBeenCalledTimes(2);
    expect(find(fixture, '[data-state="rebuild-required"]')).not.toBeNull();
    expect(limit.value).toBe('19');
  });

  it('cancels a replaced preview and renders snippet text as escaped text', async () => {
    const cancelled: string[] = [];
    const hit = { entityType: 'TASK', id: '1', title: 'Safe', description: '<img src=x onerror=alert(1)>' };
    const answer = previewAnswer('second', [{ ...hit, targetUrl: '/tasks/items/1' }]);
    const previewCall = vi.fn(
      (request: { q: string }) =>
        new Observable((subscriber) => {
          if (request.q === 'second') subscriber.next(answer);
          return () => cancelled.push(request.q);
        }),
    );
    const { fixture } = await createFixture(ADMIN, { preview: previewCall });

    preview(fixture, 'first');
    preview(fixture, 'second');
    fixture.detectChanges();

    expect(cancelled).toContain('first');
    expect(find(fixture, '.preview-hit img')).toBeNull();
    expect(find(fixture, '.preview-hit').textContent).toContain('<img src=x onerror=alert(1)>');
    expect(find(fixture, '[data-active-profile="MIXED"]')).not.toBeNull();
  });

  it('keeps the edited draft on a failed save and gives a preview failure its own alert beside the save one', async () => {
    const { fixture } = await createFixture(ADMIN, {
      save: vi.fn(() => throwError(() => problem('Временно недоступно'))),
      preview: vi.fn(() => throwError(() => problem('Preview unavailable'))),
    });
    const limit = type(fixture, '#search-global-limit', '14');
    button(fixture, 'save-search-settings')!.click();
    preview(fixture, 'failing preview');
    fixture.detectChanges();

    expect(limit.value).toBe('14');
    expect(find(fixture, '[data-state="save-error"]').textContent).toContain('Временно недоступно');
    const previewAlert = find(fixture, '[data-state="preview-error"]') as HTMLElement;
    expect(previewAlert).not.toBeNull();
    expect(previewAlert?.textContent).toContain('Preview unavailable');
  });

  it('emits the canonical numTypos field after editing a real typo control', async () => {
    const save = vi.fn((_request: SearchSettingsSnapshot) => new Subject<SearchSettingsSnapshot>());
    const { fixture, management } = await createFixture(ADMIN, {
      settings: vi.fn(() => of(structuredClone(canonicalBackendSettings))),
      save,
    });
    const typoInputs = Array.from(
      inScreen(fixture.nativeElement).querySelectorAll('.field-grid:not(.field-grid-head) label:nth-of-type(2) input'),
    ) as HTMLInputElement[];
    const editedValues = [1, 2, 2, 2, 2, 2, 2, 0, 0, 0];
    typoInputs.forEach((input, index) => {
      input.value = String(editedValues[index]);
      input.dispatchEvent(new Event('input'));
    });
    fixture.detectChanges();

    button(fixture, 'save-search-settings')!.click();
    preview(fixture, 'canonical payload');

    expect(save).toHaveBeenCalledTimes(1);
    expect(management['preview']).toHaveBeenCalledTimes(1);
    const savedField = save.mock.calls[0][0].policy.fields.TASK[0] as unknown as Record<string, unknown>;
    const previewField = management['preview'].mock.calls[0][0].policy.fields.TASK[0] as Record<string, unknown>;
    for (const field of [savedField, previewField]) {
      expect(field).toMatchObject({ field: 'title', weight: 10, numTypos: 1, prefix: true });
      expect(field).not.toHaveProperty('typos');
    }
  });

  it('asks before a rebuild and offers to repeat one whose outcome is unknown', async () => {
    const startJob = vi
      .fn()
      .mockReturnValueOnce(throwError(() => problem('Ответ неизвестен')))
      .mockReturnValueOnce(new Subject());
    const { fixture } = await createFixture(ADMIN, { startJob });

    click(fixture, 'start-rebuild');
    expect(startJob).not.toHaveBeenCalled();
    click(fixture, 'confirm-search-maintenance');
    expect(startJob).toHaveBeenCalledWith(expect.objectContaining({ action: 'REBUILD' }));
    click(fixture, 'retry-uncertain-mutation');

    expect(startJob).toHaveBeenCalledTimes(2);
  });

  it('uses only returned rollback targets and blocks rebuild at the four-generation guard', async () => {
    const full = structuredClone(status);
    full.rollbackTargets = [{ id: 'retained-1', schemaProfile: 'MIXED', lastVerifiedAt: null }];
    full.generations = Array.from({ length: 4 }, (_, index) => ({
      ...structuredClone(status.generations[0]),
      id: `generation-${index + 1}`,
      active: index === 0,
      state: index === 0 ? 'ACTIVE' : 'RETAINED',
      storageBytes: index === 3 ? null : 1024,
    }));
    const startJob = vi.fn(() => new Subject());
    const { fixture } = await createFixture(ADMIN, { status: vi.fn(() => of(full)), startJob });

    expect(button(fixture, 'start-rebuild')!.disabled).toBe(true);
    expect(find(fixture, '[data-state="generation-capacity"]')).not.toBeNull();
    expect(find(fixture, 'button[data-action*="delete"], input[name*="generation"]')).toBeNull();
    (find(fixture, 'button[data-generation-id="retained-1"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    button(fixture, 'confirm-search-maintenance')!.click();

    expect(startJob).toHaveBeenCalledWith(expect.objectContaining({ action: 'ROLLBACK', generationId: 'retained-1' }));
  });

  it('closes conflicting controls after a job receipt, keeps its cancel and stops polling locally on destruction', async () => {
    vi.useFakeTimers();
    let pollUnsubscribed = 0;
    const pollCall = vi.fn(
      () =>
        new Observable<SearchJobStatus>((subscriber) => {
          subscriber.next(job({ state: 'RUNNING', processedCount: 1 }));
          return () => pollUnsubscribed++;
        }),
    );
    const cancel = vi.fn(() => new Subject());
    const { fixture } = await createFixture(ADMIN, { job: pollCall, cancel });
    type(fixture, '#search-global-limit', '12');

    click(fixture, 'start-check');
    for (const action of ['save-search-settings', 'start-check', 'start-rebuild']) {
      expect(button(fixture, action)!.disabled).toBe(true);
    }
    vi.advanceTimersByTime(0);
    fixture.detectChanges();
    expect(pollCall).toHaveBeenCalledTimes(1);
    const cancelButton = find(fixture, '.active-job button') as HTMLButtonElement;
    expect(cancelButton).not.toBeNull();
    expect(cancelButton.disabled).toBe(false);
    cancelButton.click();
    expect(cancel).toHaveBeenCalledWith('job-1');

    fixture.destroy();
    vi.advanceTimersByTime(45_000);
    expect(pollUnsubscribed).toBe(1);
    expect(pollCall).toHaveBeenCalledTimes(1);
    // Leaving never asks the server to cancel: the one call is the click above.
    expect(cancel).toHaveBeenCalledTimes(1);
  });

  it('keeps maintenance closed for a job running at entry, offers to resume a failed poll and reopens at its end', async () => {
    vi.useFakeTimers();
    const running = job({ id: 'job-reentry', action: 'REBUILD', generationId: 'generation-2', state: 'RUNNING' });
    const statusCall = vi
      .fn()
      .mockReturnValueOnce(of({ ...structuredClone(status), jobs: [running] }))
      .mockReturnValue(of(structuredClone(status)));
    const resumedPoll = new Subject<SearchJobStatus>();
    const pollCall = vi
      .fn()
      .mockReturnValueOnce(throwError(() => problem('Polling failed')))
      .mockReturnValueOnce(resumedPoll);
    const { fixture, management } = await createFixture(ADMIN, { status: statusCall, job: pollCall });
    type(fixture, '#search-global-limit', '12');

    expect(button(fixture, 'save-search-settings')!.disabled).toBe(true);
    expect(button(fixture, 'start-check')!.disabled).toBe(true);
    vi.advanceTimersByTime(0);
    fixture.detectChanges();
    expect(button(fixture, 'start-check')!.disabled).toBe(true);
    click(fixture, 'resume-job-polling');
    vi.advanceTimersByTime(0);
    expect(pollCall).toHaveBeenCalledTimes(2);

    resumedPoll.next({ ...running, state: 'SUCCEEDED', finishedAt: '' });
    fixture.detectChanges();
    expect(statusCall).toHaveBeenCalledTimes(2);
    expect(management['jobs']).toHaveBeenCalledTimes(2);
    expect(button(fixture, 'save-search-settings')!.disabled).toBe(false);
    expect(button(fixture, 'start-check')!.disabled).toBe(false);
  });

  it('renders and retries an initial history failure without claiming the history is empty', async () => {
    const jobs = vi
      .fn()
      .mockReturnValueOnce(throwError(() => problem('History unavailable')))
      .mockReturnValueOnce(of({ items: [job({ id: 'job-recovered' })], hasMore: false }));
    const { fixture } = await createFixture(VIEW, { jobs });

    const error = find(fixture, '[data-state="search-history-error"]') as HTMLElement;
    expect(error).not.toBeNull();
    expect(error?.textContent).toContain('History unavailable');
    expect(fixture.nativeElement.textContent).not.toContain('История заданий пуста');
    click(fixture, 'retry-search-history');

    expect(jobs).toHaveBeenCalledTimes(2);
    expect(fixture.nativeElement.textContent).toContain('job-recovered');
    expect(find(fixture, '[data-state="search-history-error"]')).toBeNull();
  });

  it('loads the next bounded history page with the opaque server cursor, retrying a failed one with the same', async () => {
    const jobs = vi
      .fn()
      .mockReturnValueOnce(of({ items: [job()], nextCursor: 'opaque+/=', hasMore: true }))
      .mockReturnValueOnce(throwError(() => problem('Older history unavailable')))
      .mockReturnValueOnce(of({ items: [job({ id: 'job-2', createdAt: '2026-09-06T12:00:00Z' })], hasMore: false }));
    const { fixture } = await createFixture(VIEW, { jobs });

    expect(button(fixture, 'load-more-search-jobs')).not.toBeNull();
    click(fixture, 'load-more-search-jobs');
    const error = find(fixture, '[data-state="search-history-page-error"]') as HTMLElement;
    expect(error).not.toBeNull();
    expect(error?.textContent).toContain('Older history unavailable');
    expect(fixture.nativeElement.textContent).toContain('job-1');
    click(fixture, 'retry-search-history-page');

    expect(jobs).toHaveBeenNthCalledWith(2, 20, 'opaque+/=');
    expect(jobs).toHaveBeenNthCalledWith(3, 20, 'opaque+/=');
    expect(fixture.nativeElement.textContent).toContain('job-1');
    expect(fixture.nativeElement.textContent).toContain('job-2');
    expect(find(fixture, '[data-state="search-history-page-error"]')).toBeNull();
    expect(button(fixture, 'load-more-search-jobs')).toBeNull();
  });

  it('maps an unknown historical job error to the generic safe message', async () => {
    const failed = job({ id: 'failed-job', action: 'REBUILD', state: 'FAILED', errorCode: 'RAW_DOWNSTREAM_SECRET' });
    const { fixture } = await createFixture(VIEW, {
      status: vi.fn(() => of({ ...structuredClone(status), jobs: [failed] })),
    });

    const rendered = find(fixture, '[data-job-error="failed-job"]') as HTMLElement;
    expect(rendered).not.toBeNull();
    expect(rendered.textContent).toContain('Задание завершилось с безопасно скрытой ошибкой');
    expect(rendered.textContent).not.toContain('RAW_DOWNSTREAM_SECRET');
  });

  it('resolves every visible search-tab key through the packaged Russian fallback', async () => {
    const { fixture } = await createFixture(ADMIN);

    expect(fixture.nativeElement.textContent).not.toContain('settings.search.');
  });
});
