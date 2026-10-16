import { signal } from '@angular/core';
import { AuthService } from '@core/services/auth.service';
import { TestBed } from '@angular/core/testing';
import { of, Subject } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ApiService } from '@core/services/api.service';
import { PermissionService } from '@core/services/permission.service';
import { SMTModalService } from '@shared/ui-kit/components/modal';
import { NewFormAssistantComponent } from './new-form-assistant.component';
import { NewFormStore } from './new-form.store';
import { initialSimpleForm } from './new-form.models';

async function render() {
  const declaration = initialSimpleForm();
  declaration.module.code = 'inventory';
  declaration.entity.name = 'rooms';
  declaration.entity.title.ru = 'Помещения';
  const api = {
    get: vi.fn(() => of({ available: true })),
    post: vi.fn((_url: string, _body: unknown) => of(declaration)),
  };
  TestBed.configureTestingModule({
    providers: [
      NewFormStore,
      { provide: ApiService, useValue: api },
      { provide: AuthService, useValue: { currentUser: signal({ id: 1 }), isLoggingOut: signal(false) } },
    ],
  });
  TestBed.inject(PermissionService).setPermissions(['md.settings.update', 'studio.forms.assist']);
  const fixture = TestBed.createComponent(NewFormAssistantComponent);
  fixture.detectChanges();
  await fixture.whenStable();
  const component = fixture.componentInstance;
  component.store.setPrompt('A form');
  component.store.generate();
  fixture.detectChanges();
  return { fixture, component, source: TestBed.inject(NewFormStore), api };
}
describe('assistant proposal application', () => {
  it('closes a pending confirmation when its editor is destroyed', async () => {
    const { fixture, component, source } = await render();
    source.edit((draft) => {
      draft.entity.name = 'manual';
    });
    fixture.detectChanges();
    component.store.generate();
    const answer = new Subject<boolean>();
    const closed = vi.fn();
    vi.spyOn(TestBed.inject(SMTModalService), 'confirm').mockImplementation((options) => {
      options.closeWhen?.subscribe(closed);
      return answer;
    });
    component.apply();
    fixture.destroy();
    expect(closed).toHaveBeenCalledOnce();
    answer.next(true);
    expect(source.draft().entity.name).toBe('manual');
  });
  it('keeps a dirty editor on Cancel and changes it only after explicit confirmation', async () => {
    const { fixture, component, source, api } = await render();
    source.edit((draft) => {
      draft.entity.name = 'manual';
    });
    fixture.detectChanges();
    component.store.generate();
    const confirm = vi.spyOn(TestBed.inject(SMTModalService), 'confirm').mockReturnValue(of(false));
    component.apply();
    expect(confirm).toHaveBeenCalledOnce();
    expect(source.draft().entity.name).toBe('manual');
    expect(component.store.proposal()).not.toBeNull();
    confirm.mockReturnValue(of(true));
    component.apply();
    expect(source.draft().entity.name).toBe('rooms');
    expect(source.delivery()).toBe('cli');
    expect(source.prepared()).toBeNull();
    expect(component.applied()).toBe(true);
    expect(api.post.mock.calls.every((call) => call[0] === '/studio/assistant/drafts')).toBe(true);
  });
  it('cannot apply an old proposal after input changed while confirmation was pending', async () => {
    const { fixture, component, source } = await render();
    source.edit((draft) => {
      draft.entity.name = 'manual';
    });
    fixture.detectChanges();
    component.store.generate();
    const answer = new Subject<boolean>();
    vi.spyOn(TestBed.inject(SMTModalService), 'confirm').mockReturnValue(answer);
    component.apply();
    source.edit((draft) => {
      draft.entity.name = 'newer';
    });
    answer.next(true);
    expect(source.draft().entity.name).toBe('newer');
    expect(component.applied()).toBe(false);
  });
});
