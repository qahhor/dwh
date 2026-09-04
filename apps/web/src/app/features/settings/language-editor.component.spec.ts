import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/services/api.service';
import { I18nService } from '../../core/services/i18n.service';
import { PermissionService } from '../../core/services/permission.service';
import { ToastService } from '../../core/services/toast.service';
import { TranslationEditor } from '../../core/models/i18n.models';
import { LanguageEditorComponent } from './language-editor.component';
import { translateTest } from '../../../testing/i18n-test.stub';

describe('LanguageEditorComponent', () => {
  const editor: TranslationEditor = {
    language: {
      code: 'de', name: 'Deutsch', builtin: true, active: true,
      revision: 4, translated: 1, total: 2, coverage: 50
    },
    entries: [
      {
        key: 'common.save', russianValue: 'Сохранить', bundledValue: 'Speichern',
        overrideValue: null, effectiveValue: 'Speichern', translated: true
      },
      {
        key: 'feature.empty', russianValue: 'Нет данных', bundledValue: null,
        overrideValue: null, effectiveValue: 'Нет данных', translated: false
      }
    ]
  };

  async function createFixture(putResult: object = { ...editor.language, revision: 5 }) {
    const api = {
      get: vi.fn(() => of(editor)),
      put: vi.fn(() => putResult instanceof Error ? throwError(() => putResult) : of(putResult))
    };
    const i18n = {
      currentLang: signal('de'),
      refreshLanguage: vi.fn(() => of(undefined)),
      translate: translateTest
    };
    const toast = { success: vi.fn(), error: vi.fn() };
    await TestBed.configureTestingModule({
      imports: [LanguageEditorComponent],
      providers: [
        { provide: ApiService, useValue: api },
        { provide: I18nService, useValue: i18n },
        { provide: PermissionService, useValue: { hasPermission: () => true } },
        { provide: ToastService, useValue: toast }
      ]
    }).compileComponents();
    const fixture = TestBed.createComponent(LanguageEditorComponent);
    fixture.componentRef.setInput('languageCode', 'de');
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return { fixture, api, i18n, toast };
  }

  it('renders Russian source, target values, coverage and missing status', async () => {
    const { fixture } = await createFixture();

    expect(fixture.nativeElement.textContent).toContain('Deutsch');
    expect(fixture.nativeElement.textContent).toContain('50%');
    expect(fixture.nativeElement.textContent).toContain('Сохранить');
    expect(fixture.nativeElement.querySelector('[data-translation-key="feature.empty"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('[data-status="missing"]')).not.toBeNull();
  });

  it('filters missing entries and tracks edited rows', async () => {
    const { fixture } = await createFixture();
    fixture.componentInstance.missingOnly.set(true);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[data-translation-key="common.save"]')).toBeNull();

    const input = fixture.nativeElement.querySelector(
      '[data-translation-key="feature.empty"] input') as HTMLInputElement;
    input.value = 'Keine Daten';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    expect(fixture.componentInstance.dirtyCount()).toBe(1);
    expect(fixture.nativeElement.textContent).toContain('Изменено: 1');
  });

  it('saves one atomic override package and refreshes the active dictionary', async () => {
    const { fixture, api, i18n, toast } = await createFixture();
    fixture.componentInstance.setValue('feature.empty', 'Keine Daten');
    fixture.componentInstance.save();

    expect(api.put).toHaveBeenCalledWith('/i18n/admin/languages/de/translations', {
      expectedRevision: 4,
      translations: { 'feature.empty': 'Keine Daten' }
    });
    expect(i18n.refreshLanguage).toHaveBeenCalledWith('de');
    expect(toast.success).toHaveBeenCalled();
  });

  it('keeps local changes and reports a revision conflict', async () => {
    const conflict = Object.assign(new Error('conflict'), { status: 409 });
    const { fixture } = await createFixture(conflict);
    fixture.componentInstance.setValue('feature.empty', 'Keine Daten');
    fixture.componentInstance.save();

    expect(fixture.componentInstance.dirtyCount()).toBe(1);
    expect(fixture.componentInstance.saveError()).toContain('другим администратором');
  });

  it('rejects empty Russian values and malformed imports', async () => {
    const { fixture, toast } = await createFixture();
    fixture.componentRef.setInput('languageCode', 'ru');
    fixture.componentInstance.editor.set({
      ...editor,
      language: { ...editor.language, code: 'ru', name: 'Русский' },
      entries: [{ ...editor.entries[0], bundledValue: 'Сохранить', effectiveValue: 'Сохранить' }]
    });
    fixture.componentInstance.resetDraft();
    fixture.componentInstance.setValue('common.save', '');

    fixture.componentInstance.save();
    expect(toast.error).toHaveBeenCalledWith(expect.stringContaining('Русский перевод'));

    expect(fixture.componentInstance.importJson('{bad json')).toBe(false);
    expect(toast.error).toHaveBeenCalledWith(expect.stringContaining('JSON'));
  });
});
