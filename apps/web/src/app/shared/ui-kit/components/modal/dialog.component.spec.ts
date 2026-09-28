// @vitest-environment jsdom
import '@angular/compiler';
import { Component, OnDestroy, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';
import { SMTI18nService } from '@shared/ui-kit/i18n';
import { testI18n } from '@shared/ui-kit/i18n/test-messages';
import { tickInZone } from '@shared/ui-kit/testing/zone-tick';
import { SMTDialogComponent, SMTDialogContentDirective } from './dialog.component';

let created = 0;
let destroyed = 0;

@Component({ selector: 'test-body', template: `<p class="body-text">Body</p>` })
class Body implements OnDestroy {
  constructor() {
    created++;
  }

  ngOnDestroy(): void {
    destroyed++;
  }
}

@Component({
  imports: [SMTDialogComponent, SMTDialogContentDirective, Body],
  template: `
    <button type="button" class="opener" (click)="open.set(true)">Open</button>
    <smt-dialog
      [open]="open()"
      smtTitle="Edit the note"
      smtSize="lg"
      [dismissible]="dismissible()"
      (closed)="asks = asks + 1"
    >
      <ng-template smtDialogContent>
        <test-body />
        <input class="name" aria-label="Name" [value]="name()" />
        <div footer><button type="button" class="save">Save</button></div>
      </ng-template>
    </smt-dialog>
  `,
})
class Host {
  readonly open = signal(false);
  readonly dismissible = signal(true);
  readonly name = signal('Ann');
  asks = 0;
}

@Component({
  imports: [SMTDialogComponent, SMTDialogContentDirective],
  template: `
    <smt-dialog [open]="open()" [smtTitle]="title()">
      <ng-template smtDialogContent><p>Body</p></ng-template>
    </smt-dialog>
  `,
})
class LateTitleHost {
  readonly open = signal(true);
  readonly title = signal('');
}

@Component({
  imports: [SMTDialogComponent, SMTDialogContentDirective],
  template: `
    @if (shown()) {
      <smt-dialog [open]="shown()" smtTitle="Add a language">
        <ng-template smtDialogContent><p>Body</p></ng-template>
      </smt-dialog>
    }
  `,
})
class RemovedHost {
  readonly shown = signal(true);
}

describe('SMTDialogComponent', () => {
  afterEach(() => {
    TestBed.resetTestingModule();
    document.querySelectorAll('.cdk-overlay-container').forEach((node) => node.remove());
  });

  async function render() {
    created = 0;
    destroyed = 0;
    TestBed.configureTestingModule({ providers: [{ provide: SMTI18nService, useValue: testI18n() }] });
    const fixture = TestBed.createComponent(Host);
    document.body.appendChild(fixture.nativeElement);
    const settle = async () => {
      tickInZone();
      await fixture.whenStable();
      fixture.detectChanges();
    };
    await settle();
    const dialog = () => document.querySelector('[role="dialog"]') as HTMLElement | null;
    return { fixture, settle, dialog };
  }

  it('creates its content only while open and names the dialog by its title', async () => {
    const { fixture, settle, dialog } = await render();
    expect(dialog()).toBeNull();
    expect(created).toBe(0);
    fixture.componentInstance.open.set(true);
    await settle();
    expect(created).toBe(1);
    const title = document.querySelector('.smt-modal__title') as HTMLElement;
    expect(title.textContent).toBe('Edit the note');
    expect(dialog()!.getAttribute('aria-labelledby')).toContain(title.id);
    expect(dialog()!.getAttribute('aria-modal')).toBe('true');
    expect(document.querySelector('.smt-dialog__body > [footer] .save')).not.toBeNull();
    fixture.componentInstance.open.set(false);
    await settle();
    expect(dialog()).toBeNull();
    expect(destroyed).toBe(1);
  });

  it('keeps its bindings live with the screen', async () => {
    const { fixture, settle } = await render();
    fixture.componentInstance.open.set(true);
    await settle();
    fixture.componentInstance.name.set('Anna');
    await settle();
    expect((document.querySelector('.name') as HTMLInputElement).value).toBe('Anna');
  });

  it('asks to close on the close button and Escape, and stays open until the screen closes it', async () => {
    const { fixture, settle, dialog } = await render();
    fixture.componentInstance.open.set(true);
    await settle();
    (document.querySelector('.smt-modal__close') as HTMLButtonElement).click();
    await settle();
    expect(fixture.componentInstance.asks).toBe(1);
    expect(dialog()).not.toBeNull();
    dialog()!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
    await settle();
    expect(fixture.componentInstance.asks).toBe(2);
    expect(dialog()).not.toBeNull();
  });

  it('offers no way out when it is not dismissible', async () => {
    const { fixture, settle } = await render();
    fixture.componentInstance.dismissible.set(false);
    fixture.componentInstance.open.set(true);
    await settle();
    expect(document.querySelector('.smt-modal__close')).toBeNull();
    (document.querySelector('[role="dialog"]') as HTMLElement).dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }),
    );
    await settle();
    expect(fixture.componentInstance.asks).toBe(0);
  });

  it('is named by a title that arrives after it opened', async () => {
    TestBed.configureTestingModule({ providers: [{ provide: SMTI18nService, useValue: testI18n() }] });
    const fixture = TestBed.createComponent(LateTitleHost);
    document.body.appendChild(fixture.nativeElement);
    tickInZone();
    await fixture.whenStable();
    fixture.detectChanges();

    fixture.componentInstance.title.set('New project');
    tickInZone();
    await fixture.whenStable();
    fixture.detectChanges();

    const dialog = document.querySelector('[role="dialog"]') as HTMLElement;
    const title = document.getElementById(dialog.getAttribute('aria-labelledby')!);
    expect(title?.textContent).toBe('New project');
  });

  it('takes its panel and backdrop away when the screen removes it while open', async () => {
    TestBed.configureTestingModule({ providers: [{ provide: SMTI18nService, useValue: testI18n() }] });
    const fixture = TestBed.createComponent(RemovedHost);
    document.body.appendChild(fixture.nativeElement);
    const settle = async () => {
      tickInZone();
      await fixture.whenStable();
      fixture.detectChanges();
    };
    await settle();
    expect(document.querySelector('[role="dialog"]')).not.toBeNull();

    fixture.componentInstance.shown.set(false);
    await settle();

    expect(document.querySelector('[role="dialog"]')).toBeNull();
    expect(document.querySelector('.cdk-overlay-backdrop')).toBeNull();
  });
});
