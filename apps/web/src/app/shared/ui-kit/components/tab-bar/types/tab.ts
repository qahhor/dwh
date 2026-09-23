/* Vendored from @greenwhite/ui-kit (MIT) at commit 6472beb, path components/tab-bar/types/tab.ts.
 * Per ADR-0015 this copy is ours to change; the commit above is only the
 * base for comparing later work in the kit. See NOTICE. */
import { NavigationExtras } from '@angular/router';
import { TBadgeVariant } from '../../badge/badge.component';
import { SMTIcons } from '../../../types/svg-icons.type';

export interface Tab {
  /** Stable id used by optional URL synchronization. Falls back to the tab index when omitted. */
  id?: string;
  label: string;
  icon?: SMTIcons;
  badgeVariant?: TBadgeVariant;
  badgeValue?: number | string;
  disabled?: boolean;
  routerLink?: any[];
  routerNavigationExtras?: NavigationExtras;
  exact?: boolean;
  active?: boolean;
  children?: Tab[];
}
