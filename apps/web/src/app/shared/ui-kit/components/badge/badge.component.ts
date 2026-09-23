/* Vendored from @greenwhite/ui-kit (MIT) at commit 6472beb, path components/badge/badge.component.ts.
 * Per ADR-0015: behaviour changes belong upstream; only compatibility
 * edits are made here and each is marked. See NOTICE. */
import {
  booleanAttribute,
  ChangeDetectionStrategy,
  Component,
  computed,
  ElementRef,
  inject,
  input,
  output,
} from '@angular/core';

type TBadgeAppearance = 'light' | 'dark';
import { SMTIconComponent } from '../icon/icon';
import { SMTIcons } from '../../types/svg-icons.type';
import { NgOptimizedImage } from '@angular/common';
import { manageComponentClasses } from '../../utils/manage-component-classes';

export type TBadgeSize = 'SM' | 'MD' | 'LG';
export type TBadgeType = 'badge' | 'pill' | 'modern';
export type TIconSize = string | number | 'SM' | 'MD';
export type TBadgeVariant =
  | 'primary'
  | 'brand'
  | 'gray'
  | 'error'
  | 'warning'
  | 'success'
  | 'gray-blue'
  | 'blue-light'
  | 'blue'
  | 'indigo'
  | 'purple'
  | 'pink'
  | 'orange';

@Component({
  selector: 'smt-badge',
  imports: [SMTIconComponent, NgOptimizedImage],
  templateUrl: './badge.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SMTBadgeComponent {
  private host = inject(ElementRef<HTMLElement>);

  size = input<TBadgeSize>('MD', { alias: 'smtSize' });
  variant = input<TBadgeVariant>('primary', { alias: 'smtVariant' });
  type = input<TBadgeType>('pill', { alias: 'smtType' });
  label = input<string | number>(undefined, { alias: 'smtLabel' });

  iconSize = input<TIconSize>('SM', { alias: 'smtIconSize' });
  leftIcon = input<SMTIcons | null>(null, { alias: 'smtLeftIcon' });
  rightIcon = input<SMTIcons | null>(null, { alias: 'smtRightIcon' });
  leftImgSrc = input<string>('', { alias: 'smtLeftImg' });
  hasDot = input(false, { alias: 'smtHasDot', transform: booleanAttribute });

  /** When `dark`, uses muted surfaces that match tab bar / form controls on dark panels. */
  appearance = input<TBadgeAppearance>('light', { alias: 'smtAppearance' });

  /** Brighter gray badge on dark surfaces (e.g. active tab on gray variant). */
  emphasized = input(false, { alias: 'smtEmphasized', transform: booleanAttribute });

  iconClicked = output<{ event: Event; side: 'left' | 'right' }>({
    alias: 'smtIconClicked',
  });

  iconFontSize = computed(() => {
    const iconSize = this.iconSize();
    switch (iconSize) {
      case 'SM':
        return '0.75rem';
      case 'MD':
        return '1.25rem';
      default:
        return iconSize;
    }
  });

  dotClasses = computed(() => {
    const baseClasses = 'w-1.5 h-1.5 rounded-full';

    switch (this.variant()) {
      case 'gray':
        return `${baseClasses} bg-gray-500`;
      case 'primary':
      case 'brand':
        return `${baseClasses} bg-brand-500`;
      case 'error':
        return `${baseClasses} bg-error-500`;
      case 'success':
        return `${baseClasses} bg-success-500`;
      case 'warning':
        return `${baseClasses} bg-warning-500`;
      case 'gray-blue':
        return `${baseClasses} bg-gray-blue-500`;
      case 'blue-light':
        return `${baseClasses} bg-blue-light-500`;
      case 'blue':
        return `${baseClasses} bg-blue-500`;
      case 'indigo':
        return `${baseClasses} bg-indigo-500`;
      case 'purple':
        return `${baseClasses} bg-purple-500`;
      case 'pink':
        return `${baseClasses} bg-pink-500`;
      case 'orange':
        return `${baseClasses} bg-orange-500`;
      default:
        return `${baseClasses} bg-brand-500`;
    }
  });

  private badgeClassList = computed(() => {
    const classNames: string[] = ['inline-flex', 'items-center', 'gap-1', 'font-normal', 'w-max', 'shrink-0'];
    const size = this.size();
    const variant = this.variant();
    const type = this.type();
    const isOnlyIcon = !this.label();
    const leftImgSrc = this.leftImgSrc();

    if (isOnlyIcon) {
      classNames.push('rounded-full');
      // Icon-only badges have square padding and centered icon
      switch (size) {
        case 'SM':
          classNames.push('p-1'); // 4px padding
          break;
        case 'MD':
          classNames.push('p-1.5'); // 6px padding
          break;
        case 'LG':
          classNames.push('p-2'); // 8px padding
          break;
        default:
          classNames.push('p-1.5'); // Default to MD
          break;
      }
    } else {
      if (type === 'pill') {
        classNames.push('rounded-2xl');
      } else if (type === 'modern') {
        classNames.push('rounded');
      } else {
        classNames.push('rounded');
      }

      switch (size) {
        case 'SM':
          classNames.push(type === 'pill' ? 'px-2' : 'px-1.5', 'h-5', 'text-xs');
          break;
        case 'MD':
          classNames.push(leftImgSrc ? 'ps-1 pe-2.5' : type === 'pill' ? 'px-2.5' : 'px-2', 'h-[23px]', 'text-sm');
          break;
        case 'LG':
          classNames.push(leftImgSrc ? 'ps-1.5 pe-3' : type === 'pill' ? 'px-3' : 'px-2.5', 'h-[26px]', 'text-md');
          break;
        default:
          classNames.push(leftImgSrc ? 'ps-1 pe-2.5' : type === 'pill' ? 'px-2.5' : 'px-2', 'h-[23px]', 'text-sm');
          break;
      }
    }

    if (type === 'modern') {
      if (this.appearance() === 'dark') {
        classNames.push('bg-gray-modern-800', 'ring-1', 'ring-inset', 'ring-gray-700', 'text-gray-300');
      } else {
        classNames.push('bg-white', 'ring-1', 'ring-inset', 'ring-gray-300', 'text-gray-700');
      }
      return classNames;
    }

    classNames.push(...this.getVariantColorClasses(variant, this.appearance(), this.emphasized()));
    return classNames;
  });

  constructor() {
    manageComponentClasses(this.host, this.badgeClassList);
  }

  private getVariantColorClasses(variant: TBadgeVariant, appearance: TBadgeAppearance, emphasized: boolean): string[] {
    const ring = ['ring-1', 'ring-inset'] as const;

    if (appearance === 'dark') {
      switch (variant) {
        case 'gray':
          return emphasized
            ? ['bg-gray-modern-700', ...ring, 'ring-gray-600', 'text-gray-300']
            : ['bg-gray-modern-800', ...ring, 'ring-gray-700', 'text-gray-400'];
        case 'blue-light':
          return ['bg-brand-950', ...ring, 'ring-brand-800', 'text-brand-400'];
        case 'blue':
          return ['bg-blue-950', ...ring, 'ring-blue-800', 'text-blue-300'];
        case 'gray-blue':
          return ['bg-gray-blue-950', ...ring, 'ring-gray-blue-800', 'text-gray-blue-300'];
        case 'error':
          return ['bg-error-950', ...ring, 'ring-error-800', 'text-error-300'];
        case 'warning':
          return ['bg-warning-950', ...ring, 'ring-warning-800', 'text-warning-300'];
        case 'success':
          return ['bg-success-950', ...ring, 'ring-success-800', 'text-success-300'];
        case 'indigo':
          return ['bg-indigo-950', ...ring, 'ring-indigo-800', 'text-indigo-300'];
        case 'purple':
          return ['bg-purple-950', ...ring, 'ring-purple-800', 'text-purple-300'];
        case 'pink':
          return ['bg-pink-950', ...ring, 'ring-pink-800', 'text-pink-300'];
        case 'orange':
          return ['bg-orange-950', ...ring, 'ring-orange-800', 'text-orange-300'];
        case 'primary':
        case 'brand':
        default:
          return ['bg-brand-950', ...ring, 'ring-brand-800', 'text-brand-300'];
      }
    }

    switch (variant) {
      case 'primary':
      case 'brand':
        return ['bg-brand-50', ...ring, 'ring-brand-200', 'text-brand-700'];
      case 'gray':
        return ['bg-gray-50', ...ring, 'ring-gray-200', 'text-gray-700'];
      case 'error':
        return ['bg-error-50', ...ring, 'ring-error-200', 'text-error-700'];
      case 'warning':
        return ['bg-warning-50', ...ring, 'ring-warning-200', 'text-warning-700'];
      case 'success':
        return ['bg-success-50', ...ring, 'ring-success-200', 'text-success-700'];
      case 'gray-blue':
        return ['bg-gray-blue-50', ...ring, 'ring-gray-blue-200', 'text-gray-blue-700'];
      case 'blue-light':
        return ['bg-blue-light-50', ...ring, 'ring-blue-light-200', 'text-blue-light-700'];
      case 'blue':
        return ['bg-blue-50', ...ring, 'ring-blue-200', 'text-blue-700'];
      case 'indigo':
        return ['bg-indigo-50', ...ring, 'ring-indigo-200', 'text-indigo-700'];
      case 'purple':
        return ['bg-purple-50', ...ring, 'ring-purple-200', 'text-purple-700'];
      case 'pink':
        return ['bg-pink-50', ...ring, 'ring-pink-200', 'text-pink-700'];
      case 'orange':
        return ['bg-orange-50', ...ring, 'ring-orange-200', 'text-orange-700'];
      default:
        return ['bg-brand-50', ...ring, 'ring-brand-200', 'text-brand-700'];
    }
  }
}
