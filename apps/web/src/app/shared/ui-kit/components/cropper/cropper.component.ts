/* Our code, after the idea of the kit's `smt-cropper` (smartup-ui-kit@6472beb,
 * components/cropper). See ADR-0015 rule 2 and NOTICE.
 *
 * Why not the kit's copy: it wrapped ngx-image-cropper, a dependency this
 * application does not carry, in 1,200 lines with its own overlay and
 * buttons. Picking a square from a photo needs much less.
 *
 * The image is shown whole; a frame over it marks the part to keep and the
 * rest is dimmed. The frame moves when dragged and grows or shrinks from its
 * corner handle, keeping `smtAspectRatio` when one is given. It is also a
 * focusable control: arrows move it, Shift with arrows resizes it, and its
 * position is announced. The area is kept in the image's own pixels, so it
 * does not depend on how large the image is shown; `toBlob()` cuts that area
 * out through a canvas (the image must be same-origin or a blob: URL).
 *
 * <smt-cropper [src]="photoUrl" [smtAspectRatio]="1" [(area)]="crop" />
 * const blob = await cropper.toBlob('image/png'); */
import {
  booleanAttribute,
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  ElementRef,
  inject,
  input,
  model,
  numberAttribute,
  signal,
  viewChild,
  ViewEncapsulation,
} from '@angular/core';
import { SMTI18nService } from '../../i18n';

/** A rectangle in the image's own pixels. */
export interface SMTCropArea {
  readonly x: number;
  readonly y: number;
  readonly width: number;
  readonly height: number;
}

let nextCropperId = 0;

function optionalNumber(value: unknown): number | null {
  return value === undefined || value === null || value === '' ? null : numberAttribute(value);
}

@Component({
  selector: 'smt-cropper',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  encapsulation: ViewEncapsulation.None,
  styleUrl: './cropper.scss',
  host: { class: 'smt-cropper', '[class.smt-cropper--disabled]': 'disabled()' },
  template: `
    <div class="smt-cropper__stage">
      <img #image class="smt-cropper__image" [src]="src()" [attr.alt]="alt()" (load)="onLoad()" draggable="false" />
      @if (frame(); as box) {
        <div
          class="smt-cropper__frame"
          role="group"
          tabindex="0"
          [attr.aria-label]="i18n.messages().cropper.area"
          [attr.aria-describedby]="helpId"
          [style.left.px]="box.left"
          [style.top.px]="box.top"
          [style.width.px]="box.width"
          [style.height.px]="box.height"
          (pointerdown)="startMove($event)"
          (keydown)="onKeydown($event)">
          <span class="smt-cropper__handle" aria-hidden="true" (pointerdown)="startResize($event)"></span>
        </div>
      }
    </div>
    <span class="smt-cropper__help" [id]="helpId">{{ i18n.messages().cropper.help }}</span>
    <span class="smt-cropper__status" aria-live="polite">{{ status() }}</span>
  `,
})
export class SMTCropperComponent {
  readonly i18n = inject(SMTI18nService);

  readonly src = input.required<string>();

  readonly alt = input('');

  /** Width divided by height of the frame, e.g. 1 for a square; none: free. */
  readonly aspectRatio = input<number | null, unknown>(null, { alias: 'smtAspectRatio', transform: optionalNumber });

  /** Smallest side of the frame, in image pixels. */
  readonly minSize = input(32, { alias: 'smtMinSize', transform: numberAttribute });

  readonly disabled = input(false, { transform: booleanAttribute });

  /** The part to keep, in image pixels; set when the image loads if empty. */
  readonly area = model<SMTCropArea | null>(null);

  private readonly image = viewChild.required<ElementRef<HTMLImageElement>>('image');

  /** The image's own size and its shown size. */
  private readonly natural = signal({ width: 0, height: 0 });

  private readonly shown = signal({ width: 0, height: 0 });

  /** The frame in shown pixels. */
  readonly frame = computed(() => {
    const area = this.area();
    const natural = this.natural();
    const shown = this.shown();
    if (!area || !natural.width || !shown.width) return null;
    const scale = shown.width / natural.width;
    return { left: area.x * scale, top: area.y * scale, width: area.width * scale, height: area.height * scale };
  });

  readonly status = computed(() => {
    const area = this.area();
    return area ? this.i18n.messages().cropper.status(Math.round(area.x), Math.round(area.y), Math.round(area.width), Math.round(area.height)) : '';
  });

  readonly helpId = `smt-cropper-help-${nextCropperId++}`;

  private readonly observer = typeof ResizeObserver === 'function' ? new ResizeObserver(() => this.measure()) : null;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.observer?.disconnect());
  }

  onLoad(): void {
    const image = this.image().nativeElement;
    this.natural.set({ width: image.naturalWidth, height: image.naturalHeight });
    this.measure();
    this.observer?.observe(image);
    if (!this.area()) this.area.set(this.initialArea());
    else this.area.set(this.fit(this.area()!));
  }

  /** Moves the frame by dx, dy image pixels, or resizes it by them. */
  nudge(dx: number, dy: number, resize = false): void {
    const area = this.area();
    if (!area || this.disabled()) return;
    this.area.set(resize ? this.resized(area, area.width + dx, area.height + dy) : this.fit({ ...area, x: area.x + dx, y: area.y + dy }));
  }

  onKeydown(event: KeyboardEvent): void {
    const step = Math.max(1, Math.round(this.natural().width / 100));
    const moves: Record<string, [number, number]> = {
      ArrowLeft: [-step, 0],
      ArrowRight: [step, 0],
      ArrowUp: [0, -step],
      ArrowDown: [0, step],
    };
    const move = moves[event.key];
    if (!move) return;
    event.preventDefault();
    this.nudge(move[0], move[1], event.shiftKey);
  }

  startMove(event: PointerEvent): void {
    this.drag(event, (area, dx, dy) => this.fit({ ...area, x: area.x + dx, y: area.y + dy }));
  }

  startResize(event: PointerEvent): void {
    event.stopPropagation();
    this.drag(event, (area, dx, dy) => this.resized(area, area.width + dx, area.height + dy));
  }

  /** The chosen area as an image file. */
  toBlob(type = 'image/png', quality?: number): Promise<Blob | null> {
    const area = this.area();
    const image = this.image().nativeElement;
    if (!area) return Promise.resolve(null);
    const canvas = document.createElement('canvas');
    canvas.width = Math.round(area.width);
    canvas.height = Math.round(area.height);
    const context = canvas.getContext('2d');
    if (!context) return Promise.resolve(null);
    context.drawImage(image, area.x, area.y, area.width, area.height, 0, 0, canvas.width, canvas.height);
    return new Promise(resolve => canvas.toBlob(resolve, type, quality));
  }

  private drag(event: PointerEvent, next: (start: SMTCropArea, dx: number, dy: number) => SMTCropArea): void {
    const start = this.area();
    if (!start || this.disabled() || event.button !== 0) return;
    event.preventDefault();
    const scale = this.natural().width / (this.shown().width || 1);
    const target = event.currentTarget as HTMLElement;
    target.focus?.();
    const move = (moveEvent: PointerEvent) => {
      this.area.set(next(start, (moveEvent.clientX - event.clientX) * scale, (moveEvent.clientY - event.clientY) * scale));
    };
    const stop = () => {
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', stop);
    };
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', stop);
  }

  private measure(): void {
    const image = this.image().nativeElement;
    this.shown.set({ width: image.clientWidth, height: image.clientHeight });
  }

  /** The largest frame of the ratio, centred. */
  private initialArea(): SMTCropArea {
    const { width, height } = this.natural();
    const ratio = this.aspectRatio();
    let w = width;
    let h = height;
    if (ratio) {
      if (width / height > ratio) w = height * ratio;
      else h = width / ratio;
    }
    return { x: (width - w) / 2, y: (height - h) / 2, width: w, height: h };
  }

  /** A new size from the frame's top-left corner, kept to the ratio, the minimum and the image. */
  private resized(area: SMTCropArea, width: number, height: number): SMTCropArea {
    const natural = this.natural();
    const ratio = this.aspectRatio();
    const min = this.minSize();
    let w = Math.max(min, Math.min(width, natural.width - area.x));
    let h = Math.max(min, Math.min(height, natural.height - area.y));
    if (ratio) {
      h = w / ratio;
      if (area.y + h > natural.height) {
        h = natural.height - area.y;
        w = h * ratio;
      }
    }
    return { x: area.x, y: area.y, width: w, height: h };
  }

  /** The frame kept inside the image. */
  private fit(area: SMTCropArea): SMTCropArea {
    const natural = this.natural();
    const width = Math.min(area.width, natural.width);
    const height = Math.min(area.height, natural.height);
    return {
      x: Math.min(Math.max(0, area.x), natural.width - width),
      y: Math.min(Math.max(0, area.y), natural.height - height),
      width,
      height,
    };
  }
}
