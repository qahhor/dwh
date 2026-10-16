import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ApiService } from '@core/services/api.service';
import { I18nService } from '@core/services/i18n.service';
import { STUDIO_DRAFT, STUDIO_PREVIEW, STUDIO_VERSION } from '@testing/form-studio';
import { FormStudioLifecycleComponent } from './form-studio-lifecycle.component';
import { FormStudioStore } from './form-studio.store';
import { FormStudioLifecycleStore } from './form-studio-lifecycle.store';
import { FormStudioVersionsStore } from './form-studio-versions.store';

describe('FormStudioLifecycleComponent', () => {
  async function render(missing = false) {
    const api = {
      get: vi.fn((path) =>
        of(
          structuredClone(
            path.endsWith('/prototype')
              ? STUDIO_PREVIEW
              : path.endsWith('/versions')
                ? { items: [], nextCursor: null, hasMore: false, totalReturned: 0 }
                : missing
                  ? { draft: null, meta: STUDIO_PREVIEW.meta }
                  : STUDIO_DRAFT,
          ),
        ),
      ),
      post: vi.fn().mockReturnValue(of(structuredClone(STUDIO_DRAFT))),
      put: vi.fn(),
    };
    await TestBed.configureTestingModule({
      imports: [FormStudioLifecycleComponent],
      providers: [
        FormStudioStore,
        FormStudioLifecycleStore,
        FormStudioVersionsStore,
        { provide: ApiService, useValue: api },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(FormStudioLifecycleComponent);
    fixture.componentInstance.lifecycle.load();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return { fixture, component: fixture.componentInstance, root: fixture.nativeElement as HTMLElement, api };
  }
  it('creates a shared server draft and exposes its revision before version preparation', async () => {
    const { root, fixture, component, api } = await render(true);
    expect(root.textContent).toContain('Черновик ещё не сохранён на сервере');
    const save = [...root.querySelectorAll('button')].find((button) =>
      button.textContent?.includes('Сохранить черновик'),
    )!;
    save.click();
    fixture.detectChanges();
    expect(api.post).toHaveBeenCalledTimes(1);
    expect(root.textContent).toContain('Ревизия черновика: 1');
    expect(component.lifecycle.canSeal()).toBe(true);
  });
  it('explains a conflict and asks the parent to reload without changing local input', async () => {
    const { root, fixture, component, api } = await render();
    component.editor.edit((draft) => draft.sections[0].fields.reverse());
    api.put.mockReturnValue(
      throwError(() => ({ status: 409, code: 'revision_conflict', detail: 'Changed elsewhere' })),
    );
    component.lifecycle.save();
    fixture.detectChanges();
    expect(root.querySelector('[data-testid="studio-draft-conflict"]')?.textContent).toContain(
      'Ваши правки сохранены на экране',
    );
    const local = structuredClone(component.editor.draft());
    const reload = vi.fn();
    component.reload.subscribe(reload);
    const button = [...root.querySelectorAll('button')].find((button) =>
      button.textContent?.includes('Загрузить с сервера'),
    )!;
    button.click();
    expect(reload).toHaveBeenCalledOnce();
    expect(component.editor.draft()).toEqual(local);
    expect(component.lifecycle.revision()).toBe(1);
  });
  it('offers dependency recovery without artificial layout edits or a revision-conflict instruction', async () => {
    const { root, fixture, component, api } = await render();
    const declaration = structuredClone(component.editor.draft());
    api.post.mockReturnValueOnce(
      throwError(() => ({ status: 409, messageKey: 'error.md.form_studio_dependencies_changed' })),
    );
    component.lifecycle.seal();
    fixture.detectChanges();
    const save = [...root.querySelectorAll('button')].find((button) =>
      button.textContent?.includes('Сохранить черновик'),
    )!;
    const seal = [...root.querySelectorAll('button')].find((button) =>
      button.textContent?.includes('Подготовить версию'),
    )!;
    expect(save.disabled).toBe(false);
    expect(seal.disabled).toBe(true);
    expect(root.querySelector('[data-testid="studio-draft-conflict"]')).toBeNull();
    expect(root.querySelector('[data-testid="studio-dependency-conflict"]')?.textContent).toContain(
      'Повторно сохраните текущий макет',
    );
    component.lifecycle.load();
    fixture.detectChanges();
    expect(save.disabled).toBe(false);
    expect(root.querySelector('[data-testid="studio-dependency-conflict"]')).not.toBeNull();
    api.put.mockReturnValue(of({ ...STUDIO_DRAFT, draft: { ...STUDIO_DRAFT.draft!, revision: 2 } }));
    save.click();
    fixture.detectChanges();
    expect(component.editor.draft()).toEqual(declaration);
    expect(component.lifecycle.revision()).toBe(2);
    expect(root.querySelector('[data-testid="studio-dependency-conflict"]')).toBeNull();
    expect(seal.disabled).toBe(false);
    api.post.mockReturnValueOnce(of(STUDIO_VERSION));
    seal.click();
    fixture.detectChanges();
    expect(component.versions.selected()?.version).toBe(1);
  });
  it('formats version timestamps using the active platform locale', async () => {
    const { root, fixture, component } = await render();
    const createdAt = '2026-10-15T00:00:00Z';
    component.versions.items.set([{ version: 1, sourceRevision: 1, checksum: 'a'.repeat(64), createdAt }]);
    fixture.detectChanges();
    const expected = TestBed.inject(I18nService).formatDate(createdAt, { dateStyle: 'short', timeStyle: 'short' });
    expect(root.querySelector('time')?.textContent?.trim()).toBe(expected);
    expect(root.querySelector('time')?.getAttribute('datetime')).toBe(createdAt);
  });
  it('shows actionable field paths for a rejected draft save', async () => {
    const { root, fixture, component, api } = await render();
    component.editor.edit((draft) => draft.sections[0].fields.reverse());
    api.put.mockReturnValue(
      throwError(() => ({
        status: 422,
        detail: 'Invalid declaration',
        errors: [{ field: 'sections[0].fields[0].labels.ru', message: 'Required label' }],
      })),
    );
    component.lifecycle.save();
    fixture.detectChanges();
    const error = root.querySelector('[data-testid="studio-lifecycle-error"]');
    expect(error?.textContent).toContain('sections[0].fields[0].labels.ru');
    expect(error?.textContent).toContain('Required label');
    expect(component.lifecycle.conflict()).toBe(false);
    expect(component.lifecycle.serverDirty()).toBe(true);
  });
});
