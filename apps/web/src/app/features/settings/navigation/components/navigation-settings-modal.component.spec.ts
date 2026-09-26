import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { NavigationSettingsModalComponent } from './navigation-settings-modal.component';
import { I18nService } from '../../../../core/services/i18n.service';
import { translateTest } from '../../../../../testing/i18n-test.stub';

describe('NavigationSettingsModalComponent — who sees the item', () => {
  function create() {
    TestBed.configureTestingModule({
      imports: [NavigationSettingsModalComponent],
      providers: [{ provide: I18nService, useValue: { translate: translateTest, currentLang: () => 'ru' } }]
    });
    const modal = TestBed.createComponent(NavigationSettingsModalComponent).componentInstance;
    modal.permissionChoices = [{ permission: 'tasks.items.view', formName: 'Tasks', actionName: 'View' }];
    return modal;
  }

  it('offers catalog pairs by their names', () => {
    expect(create().permissionOptions()).toEqual([{ id: 'tasks.items.view', label: 'Tasks — View' }]);
  });

  it('keeps a stored pair missing from the catalog visible by its key, so it is not dropped silently', () => {
    const modal = create();
    modal.formRequiredPermission = 'legacy.form.view';
    expect(modal.permissionOptions().map(option => option.id)).toEqual(['legacy.form.view', 'tasks.items.view']);
  });
});
