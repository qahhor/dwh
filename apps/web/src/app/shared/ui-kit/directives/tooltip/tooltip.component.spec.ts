/* Not vendored: tests for the tooltip bubble (ADR-0015 rule 2); the directive has its own spec. */
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { SMTTooltipInternalComponent } from './tooltip.component';

function render(inputs: Record<string, unknown>) {
  const fixture = TestBed.createComponent(SMTTooltipInternalComponent);
  for (const [name, value] of Object.entries(inputs)) fixture.componentRef.setInput(name, value);
  fixture.detectChanges();
  const bubble = fixture.nativeElement as HTMLElement;
  const lines = () => Array.from(bubble.querySelectorAll(':scope > div')).map((line) => line.textContent?.trim());
  return { fixture, bubble, lines };
}

describe('SMTTooltipInternalComponent', () => {
  it('shows its text, and the supporting text below it only when there is some', () => {
    const plain = render({ text: 'Экспорт в Excel' });
    expect(plain.lines()).toEqual(['Экспорт в Excel', '']);

    const { fixture, lines } = render({ text: 'Экспорт в Excel', supportingText: 'До 50 000 строк' });
    expect(lines()).toEqual(['Экспорт в Excel', 'До 50 000 строк', '']);

    fixture.componentRef.setInput('supportingText', '');
    fixture.detectChanges();
    expect(lines()).toEqual(['Экспорт в Excel', '']);
  });

  it('is a tooltip hidden from assistive technology, which reads the text from the host description', () => {
    const { bubble } = render({ text: 'Удалить' });

    expect(bubble.getAttribute('role')).toBe('tooltip');
    expect(bubble.getAttribute('aria-hidden')).toBe('true');
  });

  it('draws its arrow only when it is placed beside the host', () => {
    const arrow = (bubble: HTMLElement) => bubble.querySelector(':scope > div:last-child') as HTMLElement;

    expect(arrow(render({ text: 'Удалить' }).bubble).classList).toContain('hidden');
    expect(arrow(render({ text: 'Удалить', arrowPosition: 'top-center' }).bubble).classList).not.toContain('hidden');
  });
});
