// @vitest-environment jsdom
/* The kit shipped the tooltip without tests; these are ours (ADR-0015
 * rule 6) and cover what WCAG 1.4.13 and 4.1.2 require of it. */
import '@angular/compiler';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { SMTTooltipDirective } from './tooltip.directive';

@Component({
  standalone: true,
  imports: [SMTTooltipDirective],
  template: `<button type="button" [smtTooltip]="text()" [smtTooltipDisabled]="disabled()">Export</button>`,
})
class Host {
  readonly text = signal('Download the filtered rows');
  readonly disabled = signal(false);
}

describe('SMTTooltipDirective', () => {
  beforeEach(() => vi.useFakeTimers());

  afterEach(() => {
    vi.useRealTimers();
    document.querySelectorAll('.cdk-overlay-container, .cdk-describedby-message-container').forEach(node => node.remove());
    TestBed.resetTestingModule();
  });

  function render() {
    const fixture = TestBed.createComponent(Host);
    document.body.appendChild(fixture.nativeElement);
    fixture.detectChanges();
    const button = fixture.nativeElement.querySelector('button') as HTMLButtonElement;
    return { fixture, button };
  }

  const bubble = () => document.querySelector('.cdk-overlay-pane [role="tooltip"]');

  function description(element: HTMLElement): string {
    return (element.getAttribute('aria-describedby') ?? '')
      .split(' ')
      .filter(Boolean)
      .map(id => document.getElementById(id)?.textContent ?? '')
      .join(' ');
  }

  it('describes the host with its text even while the bubble is hidden', () => {
    const { button } = render();

    expect(bubble()).toBeNull();
    expect(description(button)).toBe('Download the filtered rows');
  });

  it('follows text changes and drops the description when disabled', () => {
    const { fixture, button } = render();

    fixture.componentInstance.text.set('Export as CSV');
    fixture.detectChanges();
    expect(description(button)).toBe('Export as CSV');

    fixture.componentInstance.disabled.set(true);
    fixture.detectChanges();
    expect(description(button)).toBe('');
  });

  it('shows at once on keyboard focus and hides after focus leaves', () => {
    const { button } = render();

    button.dispatchEvent(new FocusEvent('focusin', { bubbles: true }));
    expect(bubble()?.textContent).toContain('Download the filtered rows');

    button.dispatchEvent(new FocusEvent('focusout', { bubbles: true }));
    vi.advanceTimersByTime(200);
    expect(bubble()).toBeNull();
  });

  it('closes on Escape without moving focus away', () => {
    const { button } = render();
    button.dispatchEvent(new FocusEvent('focusin', { bubbles: true }));

    const escape = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
    document.body.dispatchEvent(escape);

    expect(bubble()).toBeNull();
    expect(escape.defaultPrevented).toBe(true);
  });

  it('shows after hovering and stays while the pointer moves onto the bubble', () => {
    const { button } = render();

    button.dispatchEvent(new MouseEvent('mouseover', { bubbles: true }));
    vi.advanceTimersByTime(300);
    const shown = bubble();
    expect(shown).not.toBeNull();

    button.dispatchEvent(new MouseEvent('mouseleave'));
    shown!.closest('.cdk-overlay-pane')!.dispatchEvent(new MouseEvent('mouseenter'));
    vi.advanceTimersByTime(200);
    expect(bubble()).not.toBeNull();

    shown!.closest('.cdk-overlay-pane')!.dispatchEvent(new MouseEvent('mouseleave'));
    vi.advanceTimersByTime(200);
    expect(bubble()).toBeNull();
  });

  it('does not repeat, as a description, text the host already shows', () => {
    const { fixture, button } = render();

    fixture.componentInstance.text.set('Export');
    fixture.detectChanges();

    expect(description(button)).toBe('');
  });

  it('does not show while disabled', () => {
    const { fixture, button } = render();
    fixture.componentInstance.disabled.set(true);
    fixture.detectChanges();

    button.dispatchEvent(new FocusEvent('focusin', { bubbles: true }));
    button.dispatchEvent(new MouseEvent('mouseover', { bubbles: true }));
    vi.advanceTimersByTime(300);

    expect(bubble()).toBeNull();
  });
});
