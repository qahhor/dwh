import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { ColumnResizeDirective } from './column-resize.directive';

@Component({
  imports: [ColumnResizeDirective],
  template: `<div
    [smtColumnResize]="enabled()"
    [smtColumnResizeMin]="60"
    [smtColumnResizeMax]="300"
    [smtColumnResizePreventOverflow]="false"
    [smtColumnResizeContainerWidth]="600"
    (resizeEnd)="width = $event.widthPx; changes.push($event.widthPx)"
  >
    Name
  </div>`,
})
class ResizeHost {
  readonly enabled = signal(true);
  width = 120;
  readonly changes: number[] = [];
}

async function render() {
  await TestBed.configureTestingModule({ imports: [ResizeHost] }).compileComponents();
  const fixture = TestBed.createComponent(ResizeHost);
  fixture.detectChanges();
  const cell =
    (fixture.nativeElement.querySelector('[smtColumnResize]') as HTMLElement) ??
    (fixture.nativeElement.firstElementChild as HTMLElement);
  cell.getBoundingClientRect = () => ({ width: fixture.componentInstance.width }) as DOMRect;
  const handle = cell.querySelector<HTMLElement>('.smt-column-resize-handle')!;
  return { fixture, handle };
}

describe('ColumnResizeDirective keyboard control', () => {
  it('exposes a named, focusable separator with pixel limits', async () => {
    const { handle } = await render();
    handle.dispatchEvent(new FocusEvent('focus'));
    expect(handle.tabIndex).toBe(0);
    expect(handle.getAttribute('role')).toBe('separator');
    expect(handle.getAttribute('aria-label')).toContain('Name');
    expect(handle.getAttribute('aria-valuemin')).toBe('60');
    expect(handle.getAttribute('aria-valuemax')).toBe('300');
    expect(handle.getAttribute('aria-valuenow')).toBe('120');
  });

  it('resizes by arrows, supports larger steps and clamps to declared limits', async () => {
    const { fixture, handle } = await render();
    for (const [key, shiftKey] of [
      ['ArrowRight', false],
      ['ArrowLeft', true],
      ['Home', false],
      ['ArrowLeft', false],
      ['End', false],
    ] as const) {
      const event = new KeyboardEvent('keydown', { key, shiftKey, bubbles: true, cancelable: true });
      handle.dispatchEvent(event);
      expect(event.defaultPrevented).toBe(true);
    }
    expect(fixture.componentInstance.changes).toEqual([130, 80, 60, 300]);
    expect(handle.getAttribute('aria-valuenow')).toBe('300');
  });

  it('keeps pointer dragging through the same resize output', async () => {
    const { fixture, handle } = await render();
    const pointer = (type: string, clientX: number) => {
      const event = new MouseEvent(type, { clientX, bubbles: true, cancelable: true });
      Object.defineProperties(event, {
        isPrimary: { value: true },
        pointerId: { value: 1 },
        pointerType: { value: 'mouse' },
      });
      return event;
    };
    handle.dispatchEvent(pointer('pointerdown', 100));
    document.dispatchEvent(pointer('pointermove', 140));
    document.dispatchEvent(pointer('pointerup', 140));
    expect(fixture.componentInstance.changes).toEqual([160]);
  });

  it('removes the handle from keyboard navigation while resizing is disabled', async () => {
    const { fixture, handle } = await render();
    fixture.componentInstance.enabled.set(false);
    fixture.detectChanges();
    handle.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
    expect(handle.tabIndex).toBe(-1);
    expect(fixture.componentInstance.changes).toEqual([]);
  });
});
