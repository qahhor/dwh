/* Vendored from @greenwhite/ui-kit (MIT) at commit 6472beb, path directives/skeleton/skeleton.directive.ts.
 * Per ADR-0015: behaviour changes belong upstream; only compatibility
 * edits are made here and each is marked. See NOTICE. */
import {
  computed,
  Directive,
  effect,
  EmbeddedViewRef,
  inject,
  input,
  OnDestroy,
  Renderer2,
  TemplateRef,
  ViewContainerRef,
} from '@angular/core';
import { SMTInputAppearance } from '../../components/forms/input/types/types';
import { SMTThemeService } from '../../services/theme.service';

export type TSkeletonVariant = 'light' | 'dark';

const SHARED_ANIMATION_NAME = 'smt-skeleton-shimmer';
const SHARED_STYLE_ID = 'smt-skeleton-keyframes';

let sharedStyleInstalled = false;

/** One shared keyframes rule for the whole app — avoids N `<style>` tags per table load. */
function ensureSharedSkeletonAnimation(): void {
  if (sharedStyleInstalled || typeof document === 'undefined') return;
  if (document.getElementById(SHARED_STYLE_ID)) {
    sharedStyleInstalled = true;
    return;
  }

  const style = document.createElement('style');
  style.id = SHARED_STYLE_ID;
  style.textContent = `
    @keyframes ${SHARED_ANIMATION_NAME} {
      0% { background-position: -200% 0; }
      100% { background-position: 200% 0; }
    }
  `;
  document.head.appendChild(style);
  sharedStyleInstalled = true;
}

@Directive({
  selector: '[smtSkeleton]',
  standalone: true,
})
export class SMTSkeletonDirective implements OnDestroy {
  private templateRef = inject(TemplateRef<unknown>);
  private viewContainer = inject(ViewContainerRef);
  private renderer = inject(Renderer2);
  private themeService = inject(SMTThemeService);

  /**
   * Enable/disable the skeleton state (true = show skeleton, false = show content)
   */
  smtSkeleton = input<boolean>(true);

  /**
   * Custom width for the skeleton (e.g., '100px', '200px')
   */
  smtSkeletonWidth = input<string>('100%');

  /**
   * Custom height for the skeleton (e.g., '20px', '40px')
   */
  smtSkeletonHeight = input<string>('20px');

  /**
   * Custom border-radius for the skeleton (e.g., '4px', '50%')
   */
  smtSkeletonRadius = input<string>('4px');

  /**
   * Как у полей формы: светлая/тёмная поверхность; `auto` — из SMTThemeService.
   * На тёмной поверхности — отдельная палитра шима.
   *
   * В микросинтаксисе `*smtSkeleton` вторичные ключи дают привязку **`smtSkeleton` + `Key`** с заглавной буквы
   * (`appearance:` → свойство шаблона `smtSkeletonAppearance`). Алиас `smtAppearance` нельзя: Angular
   * тогда не сопоставляет сгенерированную привязку с инпутом, и остаётся дефолт `light`.
   *
   * Примеры: `appearance: 'dark'` в `*smtSkeleton`, либо явно `[smtSkeletonAppearance]="'dark'"` на `ng-template`.
   */
  smtSkeletonAppearance = input<SMTInputAppearance>('light');

  /**
   * Уточнение на **светлой** поверхности: более контрастные полоски при `dark`.
   * На тёмой поверхности (`smtAppearance` разрешился в dark) не используется.
   */
  smtSkeletonVariant = input<TSkeletonVariant>('light');

  /**
   * Animation duration in milliseconds
   */
  smtSkeletonDuration = input<number>(1500);

  private readonly resolvedAppearance = computed<'light' | 'dark'>(() => {
    const mode = this.smtSkeletonAppearance();
    if (mode === 'light' || mode === 'dark') return mode;
    return this.themeService.resolvedTheme();
  });

  private skeletonElement: HTMLElement | null = null;
  private viewRef: EmbeddedViewRef<unknown> | null = null;
  /** Preserves inline `display` when temporarily hiding content for skeleton. */
  private readonly contentDisplayBackup = new WeakMap<HTMLElement, string>();

  constructor() {
    effect(() => {
      const isLoading = this.smtSkeleton();
      void this.resolvedAppearance();
      void this.smtSkeletonVariant();
      void this.smtSkeletonWidth();
      void this.smtSkeletonHeight();
      void this.smtSkeletonRadius();
      void this.smtSkeletonDuration();
      void this.smtSkeletonAppearance();

      if (isLoading) {
        this.showSkeleton();
      } else {
        this.showContent();
      }
    });
  }

  ngOnDestroy(): void {
    this.destroyContent();
    this.destroySkeletonElement();
  }

  private skeletonColors(): { base: string; shimmer: string } {
    if (this.resolvedAppearance() === 'dark') {
      return { base: '#252e40', shimmer: '#343e52' };
    }
    const v = this.smtSkeletonVariant();
    return v === 'light' ? { base: '#E4E7EC', shimmer: '#F2F4F7' } : { base: '#D0D5DD', shimmer: '#E4E7EC' };
  }

  private showSkeleton(): void {
    // Keep the embedded view alive — only hide it. Destroy/recreate was the main lag.
    this.setContentVisible(false);
    ensureSharedSkeletonAnimation();

    if (!this.skeletonElement) {
      const skeleton = this.renderer.createElement('div');
      this.renderer.addClass(skeleton, 'smt-skeleton');
      const comment = this.viewContainer.element.nativeElement;
      this.renderer.insertBefore(comment.parentNode, skeleton, comment);
      this.skeletonElement = skeleton;
    }

    const { base: baseColor, shimmer: shimmerColor } = this.skeletonColors();
    const el = this.skeletonElement;
    this.renderer.setStyle(el, 'display', 'block');
    this.renderer.setStyle(el, 'width', this.smtSkeletonWidth());
    this.renderer.setStyle(el, 'height', this.smtSkeletonHeight());
    this.renderer.setStyle(el, 'borderRadius', this.smtSkeletonRadius());
    this.renderer.setStyle(
      el,
      'background',
      `linear-gradient(90deg, ${baseColor} 25%, ${shimmerColor} 50%, ${baseColor} 75%)`
    );
    this.renderer.setStyle(el, 'backgroundSize', '200% 100%');
    this.renderer.setStyle(
      el,
      'animation',
      `${SHARED_ANIMATION_NAME} ${this.smtSkeletonDuration()}ms ease-in-out infinite`
    );
  }

  private showContent(): void {
    if (this.skeletonElement) {
      this.renderer.setStyle(this.skeletonElement, 'display', 'none');
      this.renderer.setStyle(this.skeletonElement, 'animation', 'none');
    }

    if (!this.viewRef) {
      this.viewRef = this.viewContainer.createEmbeddedView(this.templateRef);
      this.viewRef.detectChanges();
      return;
    }

    this.setContentVisible(true);
  }

  private setContentVisible(visible: boolean): void {
    if (!this.viewRef) return;

    for (const node of this.viewRef.rootNodes) {
      if (!(node instanceof HTMLElement)) continue;

      if (!visible) {
        if (!this.contentDisplayBackup.has(node)) {
          this.contentDisplayBackup.set(node, node.style.display);
        }
        this.renderer.setStyle(node, 'display', 'none');
        continue;
      }

      const prev = this.contentDisplayBackup.get(node);
      if (prev != null && prev !== '') {
        this.renderer.setStyle(node, 'display', prev);
      } else {
        this.renderer.removeStyle(node, 'display');
      }
      this.contentDisplayBackup.delete(node);
    }
  }

  private destroyContent(): void {
    if (!this.viewRef) return;
    this.viewContainer.clear();
    this.viewRef = null;
  }

  private destroySkeletonElement(): void {
    if (!this.skeletonElement) return;
    const parent = this.skeletonElement.parentNode;
    if (parent) {
      this.renderer.removeChild(parent, this.skeletonElement);
    }
    this.skeletonElement = null;
  }
}
