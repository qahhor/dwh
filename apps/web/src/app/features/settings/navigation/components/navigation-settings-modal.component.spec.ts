import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { form } from '@angular/forms/signals';
import { describe, expect, it } from 'vitest';
import { blankNavigationItem } from '../navigation-item-form';
import { NavigationSettingsModalComponent } from './navigation-settings-modal.component';
import { I18nService } from '@core/services/i18n.service';
import { translateTest } from '@testing/i18n-test.stub';

describe('NavigationSettingsModalComponent — who sees the item', () => {
  function create() {
    TestBed.configureTestingModule({
      imports: [NavigationSettingsModalComponent],
      providers: [{ provide: I18nService, useValue: { translate: translateTest, currentLang: () => 'ru' } }],
    });
    const model = signal(blankNavigationItem(10));
    const itemForm = TestBed.runInInjectionContext(() => form(model));
    const fixture = TestBed.createComponent(NavigationSettingsModalComponent);
    fixture.componentRef.setInput('itemForm', itemForm);
    fixture.componentRef.setInput('permissionChoices', [
      { permission: 'tasks.items.view', formName: 'Tasks', actionName: 'View' },
    ]);
    return Object.assign(fixture, { model });
  }

  it('offers catalog pairs by their names', () => {
    expect(create().componentInstance.permissionOptions()).toEqual([{ id: 'tasks.items.view', label: 'Tasks — View' }]);
  });

  it('keeps a stored pair missing from the catalog visible by its key, so it is not dropped silently', () => {
    const fixture = create();
    fixture.model.update((item) => ({ ...item, requiredPermission: 'legacy.form.view' }));
    expect(fixture.componentInstance.permissionOptions().map((option) => option.id)).toEqual([
      'legacy.form.view',
      'tasks.items.view',
    ]);
  });
});
