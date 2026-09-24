/* Vendored from @greenwhite/ui-kit (MIT) at commit 6472beb, path components/drawer/drawer.service.spec.ts.
 * Per ADR-0015 rule 6 the tests travel with the component; the CDK Overlay
 * tests at the end cover our changes. */
// @vitest-environment jsdom
import '@angular/compiler';
import { Component, DestroyRef, inject } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { tickInZone } from '../../testing/zone-tick';
import { provideLocationMocks } from '@angular/common/testing';
import { Subject, firstValueFrom } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { SMTI18nService } from '../../i18n';
import { testI18n } from '../../i18n/test-messages';
import { SMTDrawerService } from './drawer.service';
import { SMT_DRAWER_DATA, SMT_DRAWER_REF } from './drawer.types';

class DummyContent {}

function createHarness(location?: { onUrlChange: (fn: (url: string, state: unknown) => void) => () => void }) {
  const destroyRef = {
    onDestroy: vi.fn(),
  };

  const contentContainer = {
    createComponent: vi.fn(() => ({
      instance: { kind: 'content' },
    })),
  };

  const containerInstance = {
    close: vi.fn(),
    contentContainer: () => contentContainer,
  };

  const containerRef = {
    injector: {
      get: (token: unknown) => {
        if (token === DestroyRef) return destroyRef;
        throw new Error(`Unexpected token: ${String(token)}`);
      },
    },
    changeDetectorRef: { detectChanges: vi.fn() },
    instance: containerInstance,
    setInput: vi.fn(),
  };

  const backdropClick$ = new Subject<MouseEvent>();
  const overlayRef = {
    backdropClick: () => backdropClick$,
    keydownEvents: () => new Subject<KeyboardEvent>(),
    attach: vi.fn(() => containerRef),
    dispose: vi.fn(),
    backdropElement: { classList: { add: vi.fn() } },
  };

  const overlay = {
    position: () => ({
      global: () => ({
        top: () => ({
          left: () => ({
            bottom: () => ({
              right: () => ({}),
            }),
          }),
        }),
      }),
    }),
    scrollStrategies: { block: () => ({}) },
    create: vi.fn(() => overlayRef),
  };

  const service = Object.assign(Object.create(SMTDrawerService.prototype), {
    overlay,
    injector: {},
    location: location ?? { onUrlChange: vi.fn(() => () => undefined) },
  }) as SMTDrawerService;

  return { service, overlay, overlayRef, containerRef, containerInstance, backdropClick$ };
}

describe('SMTDrawerService closeOnNavigation', () => {
  function createNavigationHarness() {
    const urlListeners: ((url: string, state: unknown) => void)[] = [];
    const location = {
      onUrlChange: (fn: (url: string, state: unknown) => void) => {
        urlListeners.push(fn);
        return () => {
          const index = urlListeners.indexOf(fn);
          if (index >= 0) urlListeners.splice(index, 1);
        };
      },
    };
    return { ...createHarness(location), urlListeners };
  }

  it('closes the drawer when Location.onUrlChange fires (Router.navigate / popstate)', () => {
    const { service, urlListeners } = createNavigationHarness();

    const ref = service.open(DummyContent);
    const closedValues: unknown[] = [];
    ref.afterClosed().subscribe(value => closedValues.push(value));

    expect(urlListeners).toHaveLength(1);

    urlListeners[0]!('/upl/packages', null);

    expect(closedValues).toEqual([undefined]);
    expect(urlListeners).toHaveLength(0);
  });

  it('does not listen for URL changes when closeOnNavigation is false', () => {
    const { service, urlListeners } = createNavigationHarness();

    service.open(DummyContent, { closeOnNavigation: false });

    expect(urlListeners).toHaveLength(0);
  });

  it('unsubscribes the URL listener on manual close', () => {
    const { service, urlListeners } = createNavigationHarness();

    const ref = service.open(DummyContent);
    expect(urlListeners).toHaveLength(1);

    ref.close('picked');

    expect(urlListeners).toHaveLength(0);
  });
});

describe('SMTDrawerService closeOnBackdropClick', () => {
  it('does not close on backdrop click by default', () => {
    const { service, containerInstance, backdropClick$ } = createHarness();

    service.open(DummyContent);
    backdropClick$.next(new MouseEvent('click'));

    expect(containerInstance.close).not.toHaveBeenCalled();
  });

  it('closes on backdrop click when closeOnBackdropClick is true', () => {
    const { service, containerInstance, backdropClick$ } = createHarness();

    service.open(DummyContent, { closeOnBackdropClick: true });
    backdropClick$.next(new MouseEvent('click'));

    expect(containerInstance.close).toHaveBeenCalledTimes(1);
  });
});

describe('SMTDrawerService disposal', () => {
  it('disposes at once when nothing animates and emits the result once', () => {
    const { service, overlayRef, containerRef } = createHarness();
    const ref = service.open<string>(DummyContent);
    const values: unknown[] = [];
    ref.afterClosed().subscribe(value => values.push(value));

    ref.close('saved');
    ref.close('again');

    expect(overlayRef.dispose).toHaveBeenCalledOnce();
    expect(containerRef.setInput).not.toHaveBeenCalled();
    expect(values).toEqual(['saved']);
  });
});

@Component({
  standalone: true,
  template: `<p class="drawer-content">{{ data.name }}</p>
    <button type="button" class="pick" (click)="ref.close(data.name)">Pick</button>`,
})
class PackageDetails {
  readonly data = inject<{ name: string }>(SMT_DRAWER_DATA);
  readonly ref = inject(SMT_DRAWER_REF);
}

describe('SMTDrawerService with CDK Overlay', () => {
  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach(node => node.remove());
    document.querySelectorAll('.drawer-opener').forEach(node => node.remove());
    TestBed.resetTestingModule();
  });

  function setup() {
    TestBed.configureTestingModule({
      providers: [provideLocationMocks(), { provide: SMTI18nService, useValue: testI18n() }],
    });
    return TestBed.inject(SMTDrawerService);
  }

  function opener(): HTMLButtonElement {
    const button = document.createElement('button');
    button.className = 'drawer-opener';
    document.body.appendChild(button);
    button.focus();
    return button;
  }

  it('is a modal dialog named by its title, with a labelled close button', () => {
    const service = setup();

    service.open(PackageDetails, { title: 'Package 42', data: { name: 'sales.xlsx' } });
    tickInZone();

    const dialog = document.querySelector('[role="dialog"]')!;
    expect(dialog.getAttribute('aria-modal')).toBe('true');
    expect(document.getElementById(dialog.getAttribute('aria-labelledby')!)?.textContent).toBe('Package 42');
    expect(dialog.querySelector('.smt-drawer__close')?.getAttribute('aria-label')).toBe('Close');
    expect(dialog.querySelector('.drawer-content')?.textContent).toBe('sales.xlsx');
  });

  it('falls back to ariaLabel when there is no title', () => {
    const service = setup();

    service.open(PackageDetails, { ariaLabel: 'Package details', data: { name: 'x' } });
    tickInZone();

    const dialog = document.querySelector('[role="dialog"]')!;
    expect(dialog.getAttribute('aria-label')).toBe('Package details');
    expect(dialog.hasAttribute('aria-labelledby')).toBe(false);
  });

  it('closes on Escape, marks the key handled and returns focus to the opener', async () => {
    const service = setup();
    const trigger = opener();

    const ref = service.open(PackageDetails, { title: 'Package', data: { name: 'x' } });
    tickInZone();
    const closed = firstValueFrom(ref.afterClosed());
    const escape = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
    document.querySelector('.smt-drawer__panel')!.dispatchEvent(escape);

    await expect(closed).resolves.toBeUndefined();
    expect(escape.defaultPrevented).toBe(true);
    expect(document.querySelector('[role="dialog"]')).toBeNull();
    expect(document.activeElement).toBe(trigger);
  });

  it('lets the content close the drawer with a result', async () => {
    const service = setup();

    const ref = service.open<string>(PackageDetails, { title: 'Package', data: { name: 'sales.xlsx' } });
    tickInZone();
    const closed = firstValueFrom(ref.afterClosed());
    document.querySelector<HTMLButtonElement>('.pick')!.click();

    await expect(closed).resolves.toBe('sales.xlsx');
  });
});
