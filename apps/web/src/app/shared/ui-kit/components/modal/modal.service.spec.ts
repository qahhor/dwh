/* Vendored from @greenwhite/ui-kit (MIT) at commit 6472beb, path components/modal/modal.service.spec.ts.
 * Per ADR-0015 rule 6 the tests travel with the component; the tests after
 * the kit's four cover our changes. */
// @vitest-environment jsdom
import '@angular/compiler';
import { TestBed } from '@angular/core/testing';
import { tickInZone } from '../../testing/zone-tick';
import { Subject, firstValueFrom } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { SMTI18nService } from '../../i18n';
import { testI18n } from '../../i18n/test-messages';
import { SMTModalConfirmComponent } from './modal-confirm/modal-confirm.component';
import { SMTModalService } from './modal.service';
import type { SMTModalConfirmCloseResult } from './types/modal-confirm.types';

class DummyComponent {}

interface FakeDialogRef<R = unknown> {
  backdropClick: Subject<MouseEvent>;
  close: ReturnType<typeof vi.fn>;
  closed: Subject<R | undefined>;
  keydownEvents: Subject<KeyboardEvent>;
}

describe('SMTModalService', () => {
  function createDialogRef<R = unknown>(): FakeDialogRef<R> {
    const closed = new Subject<R | undefined>();

    return {
      backdropClick: new Subject<MouseEvent>(),
      close: vi.fn((result?: R) => {
        closed.next(result);
        closed.complete();
      }),
      closed,
      keydownEvents: new Subject<KeyboardEvent>(),
    };
  }

  function createHarness() {
    const dialogRef = createDialogRef<SMTModalConfirmCloseResult>();
    const dialog = {
      open: vi.fn(() => dialogRef),
    };
    const i18n = testI18n();
    const service = Object.assign(Object.create(SMTModalService.prototype), {
      dialog,
      i18n,
    }) as SMTModalService;

    return {
      dialog,
      dialogRef,
      service,
    };
  }

  it('manually binds backdrop and escape closing so the flags work independently', () => {
    const { dialog, dialogRef, service } = createHarness();

    service.open(DummyComponent, {
      closeOnBackdropClick: false,
      closeOnEscape: true,
    });

    dialogRef.backdropClick.next(new MouseEvent('click'));

    expect(dialogRef.close).not.toHaveBeenCalled();

    dialogRef.keydownEvents.next(new KeyboardEvent('keydown', { key: 'Enter' }));

    expect(dialogRef.close).not.toHaveBeenCalled();

    dialogRef.keydownEvents.next(new KeyboardEvent('keydown', { key: 'Escape' }));

    expect(dialogRef.close).toHaveBeenCalledOnce();
    expect(dialog.open).toHaveBeenCalledWith(
      DummyComponent,
      expect.objectContaining({
        disableClose: true,
      })
    );
  });

  it('passes timer and cancel settings through to the confirm dialog and resolves confirm actions', async () => {
    const { dialog, dialogRef, service } = createHarness();
    const onConfirm = vi.fn();

    const resultPromise = firstValueFrom(
      service.confirm({
        message: 'Delete file?',
        cancelLabel: 'Abort',
        timer: 5,
        onConfirm,
      })
    );

    expect(dialog.open).toHaveBeenCalledWith(
      SMTModalConfirmComponent,
      expect.objectContaining({
        data: expect.objectContaining({
          cancelLabel: 'Abort',
          timer: 5,
        }),
      })
    );

    dialogRef.closed.next({ action: 'confirm' });
    dialogRef.closed.complete();

    await expect(resultPromise).resolves.toBe(true);
    expect(onConfirm).toHaveBeenCalledOnce();
  });

  it('treats dismiss as cancel when a cancel flow exists', async () => {
    const { dialogRef, service } = createHarness();
    const onCancel = vi.fn();
    const onDecline = vi.fn();

    const resultPromise = firstValueFrom(
      service.confirm({
        message: 'Stop the import?',
        cancelLabel: 'Cancel import',
        onCancel,
        onDecline,
      })
    );

    dialogRef.closed.next(undefined);
    dialogRef.closed.complete();

    await expect(resultPromise).resolves.toBe(false);
    expect(onCancel).toHaveBeenCalledOnce();
    expect(onDecline).not.toHaveBeenCalled();
  });

  it('treats dismiss as decline when no cancel flow exists', async () => {
    const { dialogRef, service } = createHarness();
    const onDecline = vi.fn();

    const resultPromise = firstValueFrom(
      service.confirm({
        message: 'Leave without saving?',
        onDecline,
      })
    );

    dialogRef.closed.next(undefined);
    dialogRef.closed.complete();

    await expect(resultPromise).resolves.toBe(false);
    expect(onDecline).toHaveBeenCalledOnce();
  });

  it('sizes the panel through CDK only and styles it with one token-driven class', () => {
    const { dialog, service } = createHarness();

    service.open(DummyComponent, { width: '500px', maxWidth: '600px' });

    expect(dialog.open).toHaveBeenCalledWith(
      DummyComponent,
      expect.objectContaining({
        width: '500px',
        maxWidth: '600px',
        panelClass: 'smt-modal-panel',
        backdropClass: 'smt-modal-backdrop',
      })
    );
  });

  it('opens confirm as an alertdialog labelled by its title and described by its message', () => {
    const { dialog, service } = createHarness();

    service.confirm({ message: 'Delete the role?', destructive: true });

    const config = (dialog.open.mock.calls[0] as unknown as [unknown, Record<string, unknown>])[1];
    const data = config['data'] as Record<string, unknown>;
    expect(config['role']).toBe('alertdialog');
    expect(config['ariaModal']).toBe(true);
    expect(config['ariaLabelledBy']).toBe(data['titleId']);
    expect(config['ariaDescribedBy']).toBe(data['messageId']);
    expect(data['destructive']).toBe(true);
    expect(data['title']).toBe('Confirmation');
    expect(data['yesLabel']).toBe('Yes');
    expect(data['noLabel']).toBe('No');
  });

  it('gives every confirm dialog its own ids', () => {
    const { dialog, service } = createHarness();

    service.confirm({ message: 'First?' });
    service.confirm({ message: 'Second?' });

    const ids = dialog.open.mock.calls.map(call => {
      const data = (call as unknown as [unknown, { data: { titleId: string } }])[1].data;
      return data.titleId;
    });
    expect(new Set(ids).size).toBe(2);
  });
});

describe('SMTModalService with CDK Dialog', () => {
  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach(node => node.remove());
    TestBed.resetTestingModule();
  });

  function setup() {
    TestBed.configureTestingModule({
      providers: [{ provide: SMTI18nService, useValue: testI18n() }],
    });
    return TestBed.inject(SMTModalService);
  }

  async function settle() {
    tickInZone();
    await new Promise(resolve => setTimeout(resolve));
    tickInZone();
  }

  it('renders an accessible alertdialog whose name and description are the visible texts', async () => {
    const service = setup();

    service.confirm({ title: 'Delete role', message: 'The role will be removed.' }).subscribe();
    await settle();

    const dialog = document.querySelector('[role="alertdialog"]');
    expect(dialog).not.toBeNull();
    expect(dialog!.getAttribute('aria-modal')).toBe('true');
    const title = document.getElementById(dialog!.getAttribute('aria-labelledby')!);
    const message = document.getElementById(dialog!.getAttribute('aria-describedby')!);
    expect(title?.textContent?.trim()).toBe('Delete role');
    expect(message?.textContent?.trim()).toBe('The role will be removed.');
  });

  it('resolves true on the confirming button and false on Escape', async () => {
    const service = setup();

    const confirmed = firstValueFrom(service.confirm({ message: 'Proceed?' }));
    await settle();
    const buttons = Array.from(document.querySelectorAll<HTMLButtonElement>('.smt-modal-confirm button'));
    buttons.at(-1)!.click();
    await expect(confirmed).resolves.toBe(true);

    const escaped = firstValueFrom(service.confirm({ message: 'Proceed again?' }));
    await settle();
    const pane = document.querySelector('.smt-modal-confirm')!;
    const escape = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
    pane.dispatchEvent(escape);
    await expect(escaped).resolves.toBe(false);
    expect(escape.defaultPrevented).toBe(true);
  });
});
