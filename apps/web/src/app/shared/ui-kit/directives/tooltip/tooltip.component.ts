/* Vendored from @greenwhite/ui-kit (MIT) at commit 6472beb, path directives/tooltip/tooltip.component.ts.
 * Per ADR-0015 this copy is ours to change; the commit above is only the
 * base for comparing later work in the kit. See NOTICE.
 *
 * Differences from the kit: the bubble has role="tooltip". It is hidden
 * from assistive technology because the directive already describes the
 * host with the same text; announcing it twice would repeat it. */
import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

export type TTooltipTheme = 'light' | 'dark';

@Component({
  selector: 'smt-tooltip-internal',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    role: 'tooltip',
    'aria-hidden': 'true',
    '[class]': 'hostClasses()',
  },
  template: `
    <div class="text-xs font-semibold text-center">{{ this.text() }}</div>
    @if (this.supportingText()) {
      <div
        class="text-xs font-normal"
        [class.text-gray-500]="this.theme() === 'light'"
        [class.text-white]="this.theme() === 'dark'">
        {{ this.supportingText() }}
      </div>
    }
    <div [class]="arrowClasses()"></div>
  `,
})
export class SMTTooltipInternalComponent {
  text = input('');
  supportingText = input('');
  theme = input<TTooltipTheme>();
  arrowPosition = input('');

  hostClasses = computed(() => {
    const classes = ['flex', 'flex-col', 'w-max', 'rounded-lg', 'relative', 'transition-opacity'];
    const hasSupportingText = !!this.supportingText();

    if (hasSupportingText) {
      classes.push('p-3', 'gap-1');
    } else {
      classes.push('px-3', 'py-2');
    }

    if (this.theme() === 'light') {
      classes.push('bg-white', 'shadow-lg', 'text-gray-700');
    } else {
      if (hasSupportingText) {
        classes.push('bg-gray-900', 'text-white', 'shadow-lg');
      } else {
        classes.push('bg-gray-900/60', 'text-white', 'backdrop-blur-[40px]');
      }
    }

    return classes.join(' ');
  });

  arrowClasses = computed(() => {
    const baseClasses = 'absolute w-0 h-0 border-solid';
    const hasSupportingText = !!this.supportingText();
    const arrowPosition = this.arrowPosition();

    const colorClasses =
      this.theme() === 'light' ? 'border-white' : hasSupportingText ? 'border-gray-900' : 'border-gray-900/60';

    let positionClasses = '';
    switch (arrowPosition) {
      case 'top-center':
        positionClasses =
          'start-1/2 -translate-x-1/2 -bottom-1.5 border-l-[8px] border-r-[8px] border-t-[6px] border-l-transparent border-r-transparent';
        break;
      case 'bottom-center':
        positionClasses =
          'start-1/2 -translate-x-1/2 -top-1.5 border-l-[8px] border-r-[8px] border-b-[6px] border-l-transparent border-r-transparent';
        break;
      case 'left':
        positionClasses =
          'top-1/2 -translate-y-1/2 -end-1.5 border-t-[8px] border-b-[8px] border-l-[6px] border-t-transparent border-b-transparent';
        break;
      case 'right':
        positionClasses =
          'top-1/2 -translate-y-1/2 -start-1.5 border-t-[8px] border-b-[8px] border-r-[6px] border-t-transparent border-b-transparent';
        break;
      case 'top-left':
        positionClasses =
          'start-3 -bottom-1.5 border-l-[8px] border-r-[8px] border-t-[6px] border-l-transparent border-r-transparent';
        break;
      case 'top-right':
        positionClasses =
          'end-3 -bottom-1.5 border-l-[8px] border-r-[8px] border-t-[6px] border-l-transparent border-r-transparent';
        break;
      case 'bottom-left':
        positionClasses =
          'start-3 -top-1.5 border-l-[8px] border-r-[8px] border-b-[6px] border-l-transparent border-r-transparent';
        break;
      case 'bottom-right':
        positionClasses =
          'end-3 -top-1.5 border-l-[8px] border-r-[8px] border-b-[6px] border-l-transparent border-r-transparent';
        break;
      default:
        return 'hidden';
    }

    return `${baseClasses} ${positionClasses} ${colorClasses}`;
  });
}
