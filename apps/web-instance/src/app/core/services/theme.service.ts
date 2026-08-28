import { Injectable, signal, effect } from '@angular/core';

export type ThemeMode = 'light' | 'dark';

@Injectable({
  providedIn: 'root'
})
export class ThemeService {
  readonly currentTheme = signal<ThemeMode>(this.getInitialTheme());

  constructor() {
    effect(() => {
      const theme = this.currentTheme();
      document.documentElement.setAttribute('data-theme', theme);
      localStorage.setItem('dwh_theme', theme);
    });
  }

  toggleTheme() {
    this.currentTheme.update(t => (t === 'light' ? 'dark' : 'light'));
  }

  setTheme(theme: ThemeMode) {
    this.currentTheme.set(theme);
  }

  private getInitialTheme(): ThemeMode {
    const saved = localStorage.getItem('dwh_theme');
    if (saved === 'dark' || saved === 'light') {
      return saved;
    }
    return 'light';
  }
}
