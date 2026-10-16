// @vitest-environment jsdom
import '@angular/compiler';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { SMTTabBarComponent, SMTTabItem } from './tab-bar.component';

type Section = 'general' | 'security' | 'audit' | 'about';

@Component({
  imports: [SMTTabBarComponent],
  template: `<smt-tab-bar [tabs]="tabs()" [(value)]="section" smtAriaLabel="Sections" smtIdPrefix="s" />`,
})
class Host {
  section: Section | null = 'general';
  readonly tabs = signal<SMTTabItem<Section>[]>([
    { value: 'general', label: 'General', icon: 'tune', panelId: 'general-panel' },
    { value: 'security', label: 'Security', disabled: true },
    { value: 'audit', label: 'Audit', count: 3, countTone: 'attention' },
    { value: 'about', label: 'About', id: 'about-tab' },
  ]);
}

function setup() {
  const fixture = TestBed.createComponent(Host);
  fixture.detectChanges();
  const tabs = () => Array.from(fixture.nativeElement.querySelectorAll('[role="tab"]')) as HTMLButtonElement[];
  const press = (tab: HTMLButtonElement, key: string) => {
    tab.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }));
    fixture.detectChanges();
  };
  return { fixture, host: fixture.componentInstance, tabs, press };
}

describe('SMTTabBarComponent', () => {
  afterEach(() => {
    TestBed.resetTestingModule();
    vi.useRealTimers();
  });

  it('names the tablist, marks the chosen tab and keeps it the only tab stop', () => {
    const { fixture, tabs } = setup();
    const list = fixture.nativeElement.querySelector('[role="tablist"]') as HTMLElement;
    expect(list.getAttribute('aria-label')).toBe('Sections');
    expect(tabs().map((tab) => tab.getAttribute('aria-selected'))).toEqual(['true', 'false', 'false', 'false']);
    expect(tabs().map((tab) => tab.tabIndex)).toEqual([0, -1, -1, -1]);
    expect(tabs()[0].getAttribute('aria-controls')).toBe('general-panel');
    expect(tabs()[1].disabled).toBe(true);
  });

  it('gives tabs generated or fixed ids and reads the count as part of the name', () => {
    const { tabs } = setup();
    expect(tabs()[0].id).toBe('s-general-tab');
    expect(tabs()[3].id).toBe('about-tab');
    expect(tabs()[2].textContent?.replace(/\s+/g, ' ').trim()).toBe('Audit 3');
    expect(tabs()[2].querySelector('.smt-tab-bar__count--attention')).not.toBeNull();
  });

  it('chooses a tab on click and ignores disabled ones', () => {
    const { fixture, host, tabs } = setup();
    tabs()[2].click();
    fixture.detectChanges();
    expect(host.section).toBe('audit');
    tabs()[1].click();
    fixture.detectChanges();
    expect(host.section).toBe('audit');
  });

  it('keeps the pointer target still between pointer-down focus and click', () => {
    vi.useFakeTimers();
    const { fixture, host, tabs } = setup();
    const target = tabs()[2];
    target.scrollIntoView = vi.fn();

    target.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, pointerType: 'mouse' }));
    target.focus();
    vi.advanceTimersByTime(0);

    expect(document.activeElement).toBe(target);
    expect(target.scrollIntoView).not.toHaveBeenCalled();
    target.dispatchEvent(new PointerEvent('pointerup', { bubbles: true, pointerType: 'mouse' }));
    target.click();
    fixture.detectChanges();
    expect(host.section).toBe('audit');
  });

  it('still centres a keyboard-focused tab after a pointer selection', () => {
    vi.useFakeTimers();
    const { tabs, press } = setup();
    const target = tabs()[3];
    target.scrollIntoView = vi.fn();
    tabs()[2].dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, pointerType: 'touch' }));
    tabs()[2].focus();
    tabs()[2].click();

    press(tabs()[2], 'ArrowRight');
    vi.advanceTimersByTime(0);

    expect(document.activeElement).toBe(target);
    expect(target.scrollIntoView).toHaveBeenCalledWith({ behavior: 'instant', block: 'nearest', inline: 'center' });
  });

  it('moves with arrows past disabled tabs, wraps, and goes to the ends with Home and End', () => {
    const { host, tabs, press } = setup();
    press(tabs()[0], 'ArrowRight');
    expect(host.section).toBe('audit');
    expect(document.activeElement).toBe(tabs()[2]);
    press(tabs()[2], 'ArrowRight');
    expect(host.section).toBe('about');
    press(tabs()[3], 'ArrowRight');
    expect(host.section).toBe('general');
    press(tabs()[0], 'ArrowLeft');
    expect(host.section).toBe('about');
    press(tabs()[3], 'Home');
    expect(host.section).toBe('general');
    press(tabs()[0], 'End');
    expect(host.section).toBe('about');
    expect(tabs()[3].tabIndex).toBe(0);
  });

  it('makes the first available tab the tab stop while nothing is chosen', () => {
    const { fixture, host, tabs } = setup();
    host.section = null;
    host.tabs.update((items) => [{ ...items[0], disabled: true }, ...items.slice(1)]);
    fixture.detectChanges();
    expect(tabs().map((tab) => tab.tabIndex)).toEqual([-1, -1, 0, -1]);
  });
});
