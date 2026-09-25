import { Injectable, signal } from '@angular/core';
import { Role } from '../../../../core/models/rbac.models';

@Injectable({
  providedIn: 'root'
})
export class UserFilterService {
  readonly isFilterMenuOpen = signal<boolean>(false);

  searchQuery = '';
  selectedState = '';
  selectedRoleId: number | null = null;
  selected2fa: boolean | null = null;

  hasExtraFilters(): boolean {
    return this.selectedRoleId !== null || this.selected2fa !== null;
  }

  resetExtraFilters(onReload: () => void): void {
    this.selectedRoleId = null;
    this.selected2fa = null;
    onReload();
  }

  clearStateFilter(onReload: () => void): void {
    this.selectedState = '';
    onReload();
  }

  clear2faFilter(onReload: () => void): void {
    this.selected2fa = null;
    onReload();
  }

  hasAnyActiveFilters(): boolean {
    return !!(this.selectedRoleId !== null || this.selected2fa !== null || this.selectedState || this.searchQuery.trim());
  }

  resetAllFilters(onResetNav: () => void, onReload: () => void): void {
    this.selectedRoleId = null;
    this.selected2fa = null;
    this.selectedState = '';
    this.searchQuery = '';
    onResetNav();
    onReload();
  }

  toggleFilterMenu(event: MouseEvent): void {
    event.stopPropagation();
    this.isFilterMenuOpen.update(v => !v);
  }

  setStateFilter(state: string, onReload: () => void): void {
    this.selectedState = state;
    onReload();
  }

  getSelectedRoleName(roles: Role[]): string {
    if (!this.selectedRoleId) return '';
    const role = roles.find(r => r.id === this.selectedRoleId);
    return role ? role.name : String(this.selectedRoleId);
  }

  clearRoleFilter(onResetNav: () => void, onReload: () => void): void {
    this.selectedRoleId = null;
    onResetNav();
    onReload();
  }
}
