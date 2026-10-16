import { By } from '@angular/platform-browser';
import { NewFormAssistantComponent } from './new-form-assistant.component';
import { initialSimpleForm } from './new-form.models';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { firstValueFrom, of, Subject, throwError, type Observable } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { PermissionService } from '@core/services/permission.service';
import { ApiService } from '@core/services/api.service';
import { SMTModalService } from '@shared/ui-kit/components/modal';
import { NewFormComponent } from './new-form.component';

describe('NewFormComponent', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });
  async function render() {
    const api = {
      get: vi.fn(() => of({ enabled: true, namespace: 'studio', maxForms: 100 })),
      post: vi.fn((_url, declaration) =>
        of({ formatVersion: 1, kind: 'simple-form-package', declaration, checksum: 'a'.repeat(64) }),
      ),
    };
    await TestBed.configureTestingModule({
      imports: [NewFormComponent],
      providers: [provideRouter([]), { provide: ApiService, useValue: api }],
    }).compileComponents();
    const fixture = TestBed.createComponent(NewFormComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    return { fixture, component: fixture.componentInstance, root: fixture.nativeElement as HTMLElement, api };
  }
  it('offers a separate assistant draft only to an authorized author', async () => {
    const { fixture, root, api } = await render();
    expect(root.querySelector('[data-testid="new-form-assistant"]')).toBeNull();
    api.get.mockReturnValueOnce(of({ available: true }) as never);
    TestBed.inject(PermissionService).setPermissions(['studio.forms.assist', 'md.settings.update']);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(root.querySelector('[data-testid="new-form-assistant"]')).not.toBeNull();
    expect(root.querySelector('[data-testid="assistant-generate"]')).not.toBeNull();
    expect(api.post).not.toHaveBeenCalled();
  });

  it('blocks assistant application and generation while an imported file is being read', async () => {
    const { component, fixture, api } = await render();
    api.get.mockReturnValue(of({ available: true }) as never);
    TestBed.inject(PermissionService).setPermissions(['studio.forms.assist', 'md.settings.update']);
    fixture.detectChanges();
    const assistant = fixture.debugElement.query(By.directive(NewFormAssistantComponent)).componentInstance.store;
    const proposal = initialSimpleForm();
    proposal.entity.name = 'suggested';
    api.post.mockReturnValue(of(proposal) as never);
    assistant.setPrompt('A form');
    assistant.generate();
    expect(assistant.canApply()).toBe(true);
    let resolve!: (value: ArrayBuffer) => void;
    const bytes = new Promise<ArrayBuffer>((done) => {
      resolve = done;
    });
    const file = { size: 2, arrayBuffer: () => bytes } as File;
    component.importFile({ target: { files: [file], value: 'draft.json' } } as unknown as Event);
    expect(assistant.canApply()).toBe(false);
    expect(assistant.canGenerate()).toBe(false);
    expect(assistant.apply(assistant.proposal())).toBe(false);
    resolve(new TextEncoder().encode('{}').buffer);
    await fixture.whenStable();
  });
  it('ignores a late file read after the declaration changed', async () => {
    const { component, fixture, api } = await render();
    let resolve!: (value: ArrayBuffer) => void;
    const bytes = new Promise<ArrayBuffer>((done) => {
      resolve = done;
    });
    component.importFile({
      target: { files: [{ size: 2, arrayBuffer: () => bytes }], value: 'draft.json' },
    } as unknown as Event);
    component.store.edit((draft) => {
      draft.entity.name = 'newer';
    });
    resolve(new TextEncoder().encode('{}').buffer);
    await fixture.whenStable();
    expect(api.post).not.toHaveBeenCalled();
    expect(component.store.draft().entity.name).toBe('newer');
  });
  it('offers an explicit activation choice while keeping CLI delivery the default', async () => {
    const { component, root } = await render();
    expect(component.store.delivery()).toBe('cli');
    expect(root.querySelector('[data-testid="new-form-delivery"]')).not.toBeNull();
    expect(root.querySelector('[data-testid="new-form-activate"]')).toBeNull();
  });
  it('names the fixed live module without presenting it as a new CLI module', async () => {
    const { component, fixture, root } = await render();
    expect(root.querySelector('legend')?.textContent).toContain('Новый модуль');
    component.store.chooseDelivery('live');
    fixture.detectChanges();
    expect(root.querySelector('legend')?.textContent).toContain('Созданные формы');
    component.store.chooseDelivery('cli');
    fixture.detectChanges();
    expect(root.querySelector('legend')?.textContent).toContain('Новый модуль');
  });
  it('shows permission and disabled activation states without hiding the source editor', async () => {
    const { component, fixture, root } = await render();
    component.store.chooseDelivery('live');
    fixture.detectChanges();
    expect(root.querySelector('[data-testid="new-form-activation-denied"]')).not.toBeNull();
    expect(root.querySelector('[data-testid="new-form-activate"]')).toBeNull();
    TestBed.inject(PermissionService).setPermissions(['studio.forms.publish']);
    fixture.detectChanges();
    expect(root.querySelector<HTMLButtonElement>('[data-testid="new-form-activate"]')?.disabled).toBe(true);
    expect(root.querySelector<HTMLInputElement>('[data-testid="new-module-code"]')?.disabled).toBe(true);
    expect(root.querySelector('[data-testid="new-form-add"]')).not.toBeNull();
  });
  it('starts independently from the Products prototype and adds fields with keyboard controls', async () => {
    const { component, fixture, root, api } = await render();
    expect(api.post).not.toHaveBeenCalled();
    component.addField();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(component.store.draft().fields[0]).toMatchObject({
      id: 'field1',
      type: 'text',
      maxLength: 255,
      required: false,
      listed: true,
    });
    expect(root.querySelector('[data-testid="new-field-0"] input')).toBe(document.activeElement);
    component.addField();
    component.moveField(1, -1);
    expect(component.store.draft().fields.map((field) => field.id)).toEqual(['field2', 'field1']);
    component.removeField(0);
    expect(component.store.draft().fields.map((field) => field.id)).toEqual(['field1']);
  });
  it('shows only validated preview and invalidates it after edits', async () => {
    const { component, fixture, root } = await render();
    expect(root.querySelector('smt-entity-form')).toBeNull();
    component.store.prepare();
    fixture.detectChanges();
    expect(root.querySelector('smt-entity-form')).not.toBeNull();
    component.addField();
    fixture.detectChanges();
    expect(root.querySelector('smt-entity-form')).toBeNull();
  });
  it('retains source after server refusal and displays the retry action', async () => {
    const { component, fixture, root, api } = await render();
    component.addField();
    api.post.mockReturnValueOnce(throwError(() => ({ detail: 'Rejected' })));
    component.store.prepare();
    fixture.detectChanges();
    expect(component.store.draft().fields).toHaveLength(1);
    expect(root.textContent).toContain('Rejected');
    expect(root.querySelector('[data-testid="new-form-prepare"]')).not.toBeNull();
  });
  it('does not clear unsaved state after a failed package download', async () => {
    const { component } = await render();
    component.addField();
    component.store.prepare();
    const download = vi
      .fn()
      .mockImplementationOnce(() => {
        throw new Error('blocked');
      })
      .mockReturnValue('blob:prepared');
    vi.stubGlobal('URL', { createObjectURL: download, revokeObjectURL: vi.fn() });
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    component.downloadPackage();
    expect(component.store.dirty()).toBe(true);
    expect(component.store.error()).not.toBe('');
    component.downloadPackage();
    expect(component.store.dirty()).toBe(false);
    expect(download).toHaveBeenCalledTimes(2);
  });
  it('stops at the field limit and ignores moves beyond either end', async () => {
    const { component } = await render();
    for (let i = 0; i < 22; i++) component.addField();
    expect(component.store.draft().fields).toHaveLength(20);
    const before = component.store.draft();
    component.moveField(0, -1);
    component.moveField(19, 1);
    expect(component.store.draft()).toEqual(before);
  });
  it('guards unsaved input and blocks navigation during preparation', async () => {
    const { component, api } = await render();
    component.addField();
    const confirm = vi.spyOn(TestBed.inject(SMTModalService), 'confirm').mockReturnValue(of(false));
    expect(await firstValueFrom(component.canLeaveRecordPage() as Observable<boolean>)).toBe(false);
    expect(confirm).toHaveBeenCalled();
    const event = new Event('beforeunload', { cancelable: true }) as BeforeUnloadEvent;
    component.beforeUnload(event);
    expect(event.defaultPrevented).toBe(true);
    api.post.mockReturnValue(new Subject());
    component.store.prepare();
    expect(component.canLeaveRecordPage()).toBe(false);
  });
  it('rejects malformed UTF-8 and oversized imports without replacing source', async () => {
    const { component, api } = await render();
    component.addField();
    vi.spyOn(TestBed.inject(SMTModalService), 'confirm').mockReturnValue(of(true));
    const old = structuredClone(component.store.draft());
    component.importFile({ target: { files: [{ size: 40000 }], value: 'bad' } } as unknown as Event);
    expect(api.post).not.toHaveBeenCalled();
    component.importFile({
      target: { files: [{ size: 2, arrayBuffer: async () => new Uint8Array([0xff]).buffer }], value: 'bad' },
    } as unknown as Event);
    await Promise.resolve();
    await Promise.resolve();
    expect(api.post).not.toHaveBeenCalled();
    expect(component.store.draft()).toEqual(old);
    expect(component.store.error()).not.toBe('');
  });
  it('downloads a recovery draft even before successful validation', async () => {
    const { component, api } = await render();
    component.addField();
    const create = vi.fn().mockReturnValue('blob:draft');
    vi.stubGlobal('URL', { createObjectURL: create, revokeObjectURL: vi.fn() });
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    component.downloadDraft();
    const body = create.mock.calls[0][0] as Blob;
    expect(JSON.parse(await body.text()).kind).toBe('simple-form');
    expect(api.post).not.toHaveBeenCalled();
    expect(component.store.dirty()).toBe(false);
  });
  it('opens the discard dialog only for a subscribed navigation and closes it on cancellation', async () => {
    const { component, fixture } = await render();
    component.addField();
    const decision = component.canLeaveRecordPage() as Observable<boolean>;
    expect(document.querySelectorAll('[role="alertdialog"]')).toHaveLength(0);
    const subscription = decision.subscribe();
    fixture.detectChanges();
    expect(document.querySelectorAll('[role="alertdialog"]')).toHaveLength(1);
    subscription.unsubscribe();
    fixture.detectChanges();
    expect(document.querySelectorAll('[role="alertdialog"]')).toHaveLength(0);
  });
  it('keeps only the latest discard decision when navigation is repeated', async () => {
    const { component, fixture } = await render();
    component.addField();
    const first = vi.fn();
    (component.canLeaveRecordPage() as Observable<boolean>).subscribe(first);
    const second = (component.canLeaveRecordPage() as Observable<boolean>).subscribe();
    fixture.detectChanges();
    expect(first).toHaveBeenCalledWith(false);
    expect(document.querySelectorAll('[role="alertdialog"]')).toHaveLength(1);
    second.unsubscribe();
    fixture.detectChanges();
    expect(document.querySelectorAll('[role="alertdialog"]')).toHaveLength(0);
  });
});
