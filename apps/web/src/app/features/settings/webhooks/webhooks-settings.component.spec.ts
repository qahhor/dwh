import { TestBed } from '@angular/core/testing';
import { NEVER, of, throwError } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '@core/services/api.service';
import { I18nService } from '@core/services/i18n.service';
import { PermissionService } from '@core/services/permission.service';
import { ToastService } from '@core/services/toast.service';
import { WebhooksSettingsComponent } from './webhooks-settings.component';
import { WebhookSubscription, CreatedWebhookSubscription } from './webhooks-settings.models';
import { translateTest } from '@testing/i18n-test.stub';
import { inScreen, redraw } from '@testing/in-screen';
import { By } from '@angular/platform-browser';
import { SMTModalService } from '@shared/ui-kit/components/modal';

describe('WebhooksSettingsComponent', () => {
  // Dialogs render into the CDK overlay on document.body; each test starts without the last one's.
  afterEach(() => document.querySelectorAll('.cdk-overlay-container').forEach((node) => node.remove()));
  const mockSubscriptions: WebhookSubscription[] = [
    {
      id: 1,
      name: 'ERP Integration',
      targetUrl: 'https://erp.example.com/webhooks',
      subscribedEvents: ['task.created', 'task.updated'],
      state: 'A',
      createdAt: '2026-09-18T10:00:00Z',
      createdBy: 42,
    },
    {
      id: 2,
      name: 'Security Logger',
      targetUrl: 'https://sec.example.com/log',
      subscribedEvents: ['*'],
      state: 'P',
      createdAt: '2026-09-17T12:00:00Z',
      createdBy: 42,
    },
  ];

  async function createFixture(subscriptions: WebhookSubscription[] = mockSubscriptions, canManage = true) {
    const api = {
      get: vi.fn((url: string) => {
        if (url === '/webhooks/subscriptions') {
          return of(subscriptions);
        }
        return of([]);
      }),
      post: vi.fn((url: string, body: unknown) => {
        const created: CreatedWebhookSubscription = {
          id: 3,
          name: (body as any).name,
          targetUrl: (body as any).targetUrl,
          subscribedEvents: (body as any).subscribedEvents,
          state: 'A',
          createdAt: '2026-09-18T12:00:00Z',
          createdBy: 1,
          secretToken: 'whsec_test_secret_key_12345', // gitleaks:allow -- synthetic test secret
        };
        return of(created);
      }),
      patch: vi.fn(() => of(undefined)),
      delete: vi.fn(() => of(undefined)),
    };

    const toast = {
      success: vi.fn(),
      error: vi.fn(),
      info: vi.fn(),
      show: vi.fn(),
    };

    const permService = {
      hasPermission: vi.fn((form: string, action: string) => {
        if (form === 'webhook.subscriptions' && action === 'manage') return canManage;
        return true;
      }),
    };

    const i18nService = {
      translate: translateTest,
      currentLang: () => 'ru',
    };

    await TestBed.configureTestingModule({
      imports: [WebhooksSettingsComponent],
      providers: [
        { provide: ApiService, useValue: api },
        { provide: ToastService, useValue: toast },
        { provide: PermissionService, useValue: permService },
        { provide: I18nService, useValue: i18nService },
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(WebhooksSettingsComponent);
    const component = fixture.componentInstance;
    fixture.detectChanges();

    return { fixture, component, api, toast, permService };
  }

  it('should load subscriptions on init and render table rows', async () => {
    const { fixture, component, api } = await createFixture();
    expect(api.get).toHaveBeenCalledWith('/webhooks/subscriptions');
    expect(component.subscriptions().length).toBe(2);

    const rows = fixture.nativeElement.querySelectorAll('[role="rowgroup"] > [role="row"]');
    expect(rows.length).toBe(2);
    expect(rows[0].textContent).toContain('ERP Integration');
    expect(rows[0].textContent).toContain('https://erp.example.com/webhooks');
  });

  it('should display empty state if no subscriptions exist', async () => {
    const { fixture, component } = await createFixture([]);
    expect(component.subscriptions().length).toBe(0);

    const emptyCard = fixture.nativeElement.querySelector('.empty-card');
    expect(emptyCard).not.toBeNull();
  });

  it('should toggle state between Active and Paused', async () => {
    const { component, api, toast } = await createFixture();
    const sub = component.subscriptions()[0];

    component.toggleState(sub);

    expect(api.patch).toHaveBeenCalledWith('/webhooks/subscriptions/1', { state: 'P' }, expect.any(Object));
    expect(toast.success).toHaveBeenCalled();
  });

  it('shows a switch refused over a newer revision once and reads the list again from its button', async () => {
    const { fixture, component, api, toast } = await createFixture();
    api.patch.mockReturnValueOnce(
      throwError(() => ({ status: 409, code: 'revision_conflict', detail: 'Запись уже изменил другой пользователь' })),
    );
    const reads = api.get.mock.calls.length;

    component.toggleState(component.subscriptions()[0]);

    expect(api.patch).toHaveBeenCalledWith(
      '/webhooks/subscriptions/1',
      { state: 'P' },
      expect.objectContaining({ notifyError: false }),
    );
    expect(toast.error).not.toHaveBeenCalled();
    expect(toast.show).toHaveBeenCalledTimes(1);
    toast.show.mock.calls[0][4].run();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(api.get.mock.calls.length).toBe(reads + 1);
  });

  it('should handle creation flow and reveal secret key modal', async () => {
    const { component, api, toast } = await createFixture();

    component.openCreateModal();
    expect(component.isCreateModalOpen()).toBe(true);

    component.createModel.set({
      name: 'New CRM Webhook',
      targetUrl: 'https://crm.corp/hook',
      events: ['task.created', 'task.completed'],
    });

    expect(component.isCreateValid()).toBe(true);

    component.submitCreate();

    expect(api.post).toHaveBeenCalledWith(
      '/webhooks/subscriptions',
      {
        name: 'New CRM Webhook',
        targetUrl: 'https://crm.corp/hook',
        subscribedEvents: ['task.created', 'task.completed'],
      },
      { notifyError: false },
    );

    expect(toast.success).toHaveBeenCalled();
    expect(component.isCreateModalOpen()).toBe(false);
    expect(component.createdSecretModalOpen()).toBe(true);
    expect(component.recentlyCreatedSubscription()?.secretToken).toBe('whsec_test_secret_key_12345');

    component.closeSecretModal();
    expect(component.createdSecretModalOpen()).toBe(false);
    expect(component.recentlyCreatedSubscription()).toBeNull();
  });

  it('fills the create form from what is typed and opens it again blank and untouched', async () => {
    const { fixture, component } = await createFixture();

    component.openCreateModal();
    redraw(fixture);
    const name = inScreen(fixture.nativeElement).querySelector('#webhook-name') as HTMLInputElement;
    const url = inScreen(fixture.nativeElement).querySelector('#webhook-url') as HTMLInputElement;
    expect(name.required).toBe(true);
    expect(name.maxLength).toBe(100);
    expect(url.maxLength).toBe(500);

    name.value = '   ';
    name.dispatchEvent(new Event('input'));
    name.dispatchEvent(new Event('blur'));
    url.value = 'https://crm.corp/hook';
    url.dispatchEvent(new Event('input'));
    redraw(fixture);
    // Blank text is not a name: the save stays off and the field shows its error once left.
    expect(component.isCreateValid()).toBe(false);
    expect(fixture.debugElement.query(By.css('smt-input.smt-input--invalid'))).not.toBeNull();

    name.value = 'CRM';
    name.dispatchEvent(new Event('input'));
    redraw(fixture);
    expect(component.createModel()).toEqual({ name: 'CRM', targetUrl: 'https://crm.corp/hook', events: ['*'] });
    expect(component.isCreateValid()).toBe(true);

    component.closeCreateModal();
    component.openCreateModal();
    redraw(fixture);
    expect(component.createModel()).toEqual({ name: '', targetUrl: '', events: ['*'] });
    expect(component.createForm.name().touched()).toBe(false);
    expect(fixture.debugElement.query(By.css('smt-input.smt-input--invalid'))).toBeNull();
  });

  it('shows every error on save, under its field, and sends nothing until they are fixed', async () => {
    const { fixture, component, api } = await createFixture();
    component.openCreateModal();
    redraw(fixture);
    component.toggleAllEvents();
    component.toggleAllEvents();
    expect(component.createModel().events).toEqual([]);

    (inScreen(fixture.nativeElement).querySelector('form#webhook-create') as HTMLFormElement).requestSubmit();
    redraw(fixture);
    TestBed.tick();

    expect(api.post).not.toHaveBeenCalled();
    const root = inScreen(fixture.nativeElement);
    expect(root.querySelector('#webhook-name')?.getAttribute('aria-invalid')).toBe('true');
    expect(root.querySelector('#webhook-url')?.getAttribute('aria-invalid')).toBe('true');
    expect(root.querySelector('[data-testid="webhook-events-control"] .smt-control__error')?.textContent).toContain(
      translateTest('settings.webhooks.events_required'),
    );
    const save = root.querySelector('[data-testid="webhook-create-actions"] [data-testid="form-submit"]');
    expect((save as HTMLButtonElement).disabled).toBe(false);
  });

  it("sends one request while a save runs and puts the server's field errors under the fields", async () => {
    const { component, api, toast } = await createFixture();
    component.openCreateModal();
    component.createModel.set({ name: 'CRM', targetUrl: 'https://crm.corp/hook', events: ['*'] });

    api.post.mockReturnValueOnce(NEVER);
    component.submitCreate();
    component.submitCreate();
    expect(api.post).toHaveBeenCalledTimes(1);

    component.isSaving.set(false);
    api.post.mockReturnValueOnce(
      throwError(() => ({ status: 422, errors: [{ field: 'targetUrl', message: 'Адрес должен быть https' }] })),
    );
    component.submitCreate();
    expect(component.serverErrors()).toEqual({ targetUrl: 'Адрес должен быть https' });
    expect(toast.error).not.toHaveBeenCalled();
    expect(component.isCreateModalOpen()).toBe(true);
  });

  it('closes an untouched create dialog at once and asks before dropping typed values', async () => {
    const { component } = await createFixture();
    const confirm = vi.spyOn(TestBed.inject(SMTModalService), 'confirm').mockReturnValue(of(false));

    component.openCreateModal();
    component.requestCloseCreate();
    expect(confirm).not.toHaveBeenCalled();
    expect(component.isCreateModalOpen()).toBe(false);

    component.openCreateModal();
    component.createModel.update((model) => ({ ...model, name: 'CRM' }));
    component.requestCloseCreate();
    expect(confirm).toHaveBeenCalledWith(expect.objectContaining({ destructive: true }));
    expect(component.isCreateModalOpen()).toBe(true);
  });

  it('should handle delete confirmation and deletion', async () => {
    const { fixture, component, api, toast } = await createFixture();
    const sub = component.subscriptions()[0];

    component.confirmDelete(sub);
    await fixture.whenStable();
    const dialog = document.querySelector('.smt-modal-confirm') as HTMLElement;
    expect(dialog.textContent).toContain('ERP Integration');
    expect(api.delete).not.toHaveBeenCalled();

    [...dialog.querySelectorAll<HTMLButtonElement>('button')].at(-1)!.click();
    expect(api.delete).toHaveBeenCalledWith('/webhooks/subscriptions/1', { notifyError: false });
    expect(toast.success).toHaveBeenCalled();
    await fixture.whenStable();
    expect(document.querySelector('.smt-modal-confirm')).toBeNull();
  });
});
