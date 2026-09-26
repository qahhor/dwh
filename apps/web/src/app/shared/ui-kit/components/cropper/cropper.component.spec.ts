// @vitest-environment jsdom
import '@angular/compiler';
import { Component, viewChild } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';
import { SMTI18nService } from '../../i18n';
import { testI18n } from '../../i18n/test-messages';
import { tickInZone } from '../../testing/zone-tick';
import { SMTCropArea, SMTCropperComponent } from './cropper.component';

@Component({
  standalone: true,
  imports: [SMTCropperComponent],
  template: `<smt-cropper src="photo.png" alt="Photo" [smtAspectRatio]="1" [smtMinSize]="50" [(area)]="area" />`,
})
class Host {
  area: SMTCropArea | null = null;
  readonly cropper = viewChild.required(SMTCropperComponent);
}

describe('SMTCropperComponent', () => {
  afterEach(() => TestBed.resetTestingModule());

  /** jsdom loads no images, so the image reports a 400 x 200 picture shown at 200 x 100. */
  async function render() {
    TestBed.configureTestingModule({ providers: [{ provide: SMTI18nService, useValue: testI18n() }] });
    const fixture = TestBed.createComponent(Host);
    const settle = async () => {
      tickInZone();
      await fixture.whenStable();
      fixture.detectChanges();
    };
    await settle();
    const image = fixture.nativeElement.querySelector('img') as HTMLImageElement;
    Object.defineProperty(image, 'naturalWidth', { value: 400 });
    Object.defineProperty(image, 'naturalHeight', { value: 200 });
    Object.defineProperty(image, 'clientWidth', { value: 200 });
    Object.defineProperty(image, 'clientHeight', { value: 100 });
    image.dispatchEvent(new Event('load'));
    await settle();
    const frame = () => fixture.nativeElement.querySelector('.smt-cropper__frame') as HTMLElement;
    return { fixture, frame, settle };
  }

  it('starts on the largest centred square and draws it at the shown scale', async () => {
    const { fixture, frame } = await render();
    expect(fixture.componentInstance.area).toEqual({ x: 100, y: 0, width: 200, height: 200 });
    expect([frame().style.left, frame().style.top, frame().style.width, frame().style.height]).toEqual(['50px', '0px', '100px', '100px']);
    expect(frame().getAttribute('aria-label')).toBe('Crop area');
    expect(fixture.nativeElement.querySelector('#' + frame().getAttribute('aria-describedby'))!.textContent).toContain('arrow keys');
    expect(fixture.nativeElement.querySelector('.smt-cropper__status').textContent).toBe('200 by 200 from 100, 0');
  });

  it('moves with the arrows inside the image and resizes with Shift, keeping the square and the minimum', async () => {
    const { fixture, frame, settle } = await render();
    const key = async (name: string, shiftKey = false) => {
      frame().dispatchEvent(new KeyboardEvent('keydown', { key: name, shiftKey, bubbles: true, cancelable: true }));
      await settle();
    };
    await key('ArrowLeft');
    expect(fixture.componentInstance.area).toEqual({ x: 96, y: 0, width: 200, height: 200 });
    await key('ArrowUp');
    expect(fixture.componentInstance.area!.y).toBe(0);
    for (let i = 0; i < 60; i++) await key('ArrowLeft', true);
    expect(fixture.componentInstance.area).toEqual({ x: 96, y: 0, width: 50, height: 50 });
  });
});
