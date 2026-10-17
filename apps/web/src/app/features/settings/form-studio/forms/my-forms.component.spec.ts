import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '@core/services/api.service';
import { PermissionService } from '@core/services/permission.service';
import { SMTModalService } from '@shared/ui-kit/components/modal';
import { MyFormsComponent } from './my-forms.component';
import type { StudioFormSummary } from './my-forms.store';

function summary(code: string, change: Partial<StudioFormSummary> = {}): StudioFormSummary {
  return {
    entityCode: code,
    title: { ru: 'Визиты', uz: 'Tashriflar', en: 'Visits' },
    status: 'active',
    activeVersion: 2,
    versions: 3,
    records: 5,
    quarantined: false,
    broken: null,
    revision: 4,
    createdAt: '2026-10-17T08:00:00Z',
    modifiedAt: '2026-10-17T09:00:00Z',
    ...change,
  };
}

describe('MyFormsComponent', () => {
  afterEach(() => vi.restoreAllMocks());

  async function render(permissions: string[] = ['studio.forms.publish']) {
    const api = {
      get: vi.fn(() =>
        of({
          items: [
            summary('studio.visits'),
            summary('studio.old', { status: 'archived' }),
            summary('studio.held', { quarantined: true, broken: 'declaration' }),
          ],
          hasMore: false,
        }),
      ),
      post: vi.fn(() => of(summary('studio.visits', { status: 'archived', revision: 5 }))),
    };
    const modal = { confirm: vi.fn(() => of(true)) };
    await TestBed.configureTestingModule({
      imports: [MyFormsComponent],
      providers: [
        provideRouter([]),
        { provide: ApiService, useValue: api },
        { provide: SMTModalService, useValue: modal },
      ],
    }).compileComponents();
    TestBed.inject(PermissionService).setPermissions(permissions);
    const fixture = TestBed.createComponent(MyFormsComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return { fixture, root: fixture.nativeElement as HTMLElement, api, modal };
  }

  it('lists every form with its status, version and the actions its state allows', async () => {
    const { root } = await render();
    expect(root.querySelector('[data-testid="my-forms-status-studio.visits"]')?.textContent).toContain('Активна');
    expect(root.querySelector('[data-testid="my-forms-status-studio.old"]')?.textContent).toContain('В архиве');
    const held = root.querySelector('[data-testid="my-forms-status-studio.held"]')?.textContent ?? '';
    expect(held).toContain('Выведена из работы');
    expect(held).toContain('Не загружается');
    expect(root.textContent).toContain('2 из 3');
    expect(root.querySelector('[data-testid="my-forms-version-studio.visits"]')).not.toBeNull();
    expect(root.querySelector('[data-testid="my-forms-version-studio.old"]')).toBeNull();
    expect(root.querySelector('[data-testid="my-forms-restore-studio.old"]')).not.toBeNull();
    expect(root.querySelector('[data-testid="my-forms-archive-studio.visits"]')).not.toBeNull();
    const links = Array.from(root.querySelectorAll('a')).map((link) => link.getAttribute('href'));
    expect(links).toContain('/e/studio.visits');
    expect(links).not.toContain('/e/studio.held');
  });

  it('archives a form from its row after the confirmation', async () => {
    const { fixture, root, api, modal } = await render();
    (root.querySelector('[data-testid="my-forms-archive-studio.visits"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(modal.confirm).toHaveBeenCalled();
    expect(api.post).toHaveBeenCalledWith('/studio/forms/studio.visits/archive', null, {
      notifyError: false,
      ifMatch: 4,
    });
  });

  it('explains a missing right instead of showing an empty list', async () => {
    const { root, api } = await render([]);
    expect(root.querySelector('[data-testid="my-forms-denied"]')).not.toBeNull();
    expect(api.get).not.toHaveBeenCalled();
  });
});
