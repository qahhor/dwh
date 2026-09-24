// @vitest-environment jsdom
/* The kit shipped the hotkeys service without tests; these are ours
 * (ADR-0015 rule 6) and cover the kit's behaviour and our changes. */
import '@angular/compiler';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { SMTHotkeyDirective } from '../directives/hotkey/hotkey.directive';
import { formatHotkey, normalizeHotkey, SMTHotkeysService } from './hotkeys.service';

function press(init: KeyboardEventInit, target: EventTarget = document.body): KeyboardEvent {
  const event = new KeyboardEvent('keydown', { bubbles: true, cancelable: true, ...init });
  target.dispatchEvent(event);
  return event;
}

/** jsdom lays nothing out; give an element a box so it counts as visible. */
function visible<T extends HTMLElement>(element: T): T {
  element.getClientRects = () => [{}] as unknown as DOMRectList;
  return element;
}

describe('hotkey notation', () => {
  it('normalizes modifier order and key aliases', () => {
    expect(normalizeHotkey('Shift+Ctrl+S')).toBe('ctrl+shift+s');
    expect(normalizeHotkey('control+return')).toBe('ctrl+enter');
    expect(normalizeHotkey('esc')).toBe('escape');
  });

  it('spells combinations the way aria-keyshortcuts expects', () => {
    expect(formatHotkey('alt+s')).toBe('Alt+S');
    expect(formatHotkey('ctrl+enter')).toBe('Control+Enter');
    expect(formatHotkey('f2')).toBe('F2');
    expect(formatHotkey('shift+up')).toBe('Shift+ArrowUp');
  });
});

describe('SMTHotkeysService', () => {
  afterEach(() => {
    document.body.innerHTML = '';
    TestBed.resetTestingModule();
  });

  function service() {
    return TestBed.inject(SMTHotkeysService);
  }

  it('runs the action and prevents the browser default', () => {
    const action = vi.fn();
    service().register({ key: 'alt+s', action });

    const event = press({ key: 's', code: 'KeyS', altKey: true });

    expect(action).toHaveBeenCalledOnce();
    expect(event.defaultPrevented).toBe(true);
  });

  it('matches Ctrl combinations by the physical key, so a Russian layout works', () => {
    const action = vi.fn();
    service().register({ key: 'ctrl+s', action });

    press({ key: 'ы', code: 'KeyS', ctrlKey: true });

    expect(action).toHaveBeenCalledOnce();
  });

  it('matches Alt combinations whose key the platform replaced (Mac Option)', () => {
    const action = vi.fn();
    service().register({ key: 'alt+a', action });

    press({ key: 'å', code: 'KeyA', altKey: true });

    expect(action).toHaveBeenCalledOnce();
  });

  it('ignores keys typed into a field unless allowed there', () => {
    const blocked = vi.fn();
    const allowed = vi.fn();
    service().register({ key: 'ctrl+enter', action: blocked });
    const input = document.body.appendChild(document.createElement('input'));

    press({ key: 'Enter', code: 'Enter', ctrlKey: true }, input);
    expect(blocked).not.toHaveBeenCalled();

    service().register({ key: 'ctrl+enter', action: allowed, allowInInputs: true });
    press({ key: 'Enter', code: 'Enter', ctrlKey: true }, input);
    expect(allowed).toHaveBeenCalledOnce();
  });

  it('runs only the highest-priority enabled registration, newest first on a tie', () => {
    const low = vi.fn();
    const high = vi.fn();
    const disabled = vi.fn();
    service().register({ key: 'f2', action: low });
    service().register({ key: 'f2', action: high, priority: 5 });
    service().register({ key: 'f2', action: disabled, priority: 10, enabled: false });

    press({ key: 'F2', code: 'F2' });

    expect(high).toHaveBeenCalledOnce();
    expect(low).not.toHaveBeenCalled();
    expect(disabled).not.toHaveBeenCalled();
  });

  it('ignores auto-repeat unless asked', () => {
    const action = vi.fn();
    service().register({ key: 'alt+r', action });

    press({ key: 'r', code: 'KeyR', altKey: true, repeat: true });

    expect(action).not.toHaveBeenCalled();
  });

  it('stops working after unregister', () => {
    const action = vi.fn();
    service().register({ key: 'alt+d', action }).unregister();

    press({ key: 'd', code: 'KeyD', altKey: true });

    expect(action).not.toHaveBeenCalled();
  });

  it('does not press a page button while a modal dialog is open over it', () => {
    const page = visible(document.body.appendChild(document.createElement('button')));
    const pageAction = vi.fn();
    service().register({ key: 'alt+s', action: pageAction, element: page });
    const dialog = document.body.appendChild(document.createElement('div'));
    dialog.setAttribute('aria-modal', 'true');
    const inside = visible(dialog.appendChild(document.createElement('button')));
    const dialogAction = vi.fn();

    press({ key: 's', code: 'KeyS', altKey: true });
    expect(pageAction).not.toHaveBeenCalled();

    service().register({ key: 'alt+s', action: dialogAction, element: inside });
    press({ key: 's', code: 'KeyS', altKey: true });
    expect(dialogAction).toHaveBeenCalledOnce();
    expect(pageAction).not.toHaveBeenCalled();
  });

  it('skips elements that are hidden from assistive technology or not rendered', () => {
    const hiddenParent = document.body.appendChild(document.createElement('div'));
    hiddenParent.setAttribute('aria-hidden', 'true');
    const hidden = visible(hiddenParent.appendChild(document.createElement('button')));
    const unrendered = document.body.appendChild(document.createElement('button'));
    const action = vi.fn();
    service().register({ key: 'alt+e', action, element: hidden });
    service().register({ key: 'alt+e', action, element: unrendered });

    press({ key: 'e', code: 'KeyE', altKey: true });

    expect(action).not.toHaveBeenCalled();
  });
});

@Component({
  standalone: true,
  imports: [SMTHotkeyDirective],
  template: `<button type="button" smtHotkey="save" [disabled]="disabled()" (click)="saved = saved + 1">Save</button>`,
})
class ToolbarHost {
  readonly disabled = signal(false);
  saved = 0;
}

describe('SMTHotkeyDirective', () => {
  afterEach(() => {
    document.body.innerHTML = '';
    TestBed.resetTestingModule();
  });

  function render() {
    const fixture = TestBed.createComponent(ToolbarHost);
    document.body.appendChild(fixture.nativeElement);
    fixture.detectChanges();
    const button = visible(fixture.nativeElement.querySelector('button') as HTMLButtonElement);
    return { fixture, button };
  }

  it('announces the preset shortcut and clicks the host when it is pressed', () => {
    const { fixture, button } = render();

    expect(button.getAttribute('aria-keyshortcuts')).toBe('Alt+S');
    press({ key: 's', code: 'KeyS', altKey: true });

    expect(fixture.componentInstance.saved).toBe(1);
  });

  it('leaves a disabled button alone', () => {
    const { fixture } = render();
    fixture.componentInstance.disabled.set(true);
    fixture.detectChanges();

    press({ key: 's', code: 'KeyS', altKey: true });

    expect(fixture.componentInstance.saved).toBe(0);
  });

  it('unregisters when the host is destroyed', () => {
    const { fixture } = render();
    fixture.destroy();

    const event = press({ key: 's', code: 'KeyS', altKey: true });

    expect(event.defaultPrevented).toBe(false);
  });
});
