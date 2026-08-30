import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';
import { CpApiService } from '../core/cp-api.service';
import { ShellComponent } from './shell.component';

describe('ShellComponent', () => {
  it('provides skip navigation, named navigation and a focusable main landmark', async () => {
    await TestBed.configureTestingModule({
      imports: [ShellComponent],
      providers: [
        provideRouter([]),
        {
          provide: CpApiService,
          useValue: {
            user: signal({ login: 'ops', name: 'Оператор', roles: ['cp-admin'] }),
            logout: vi.fn().mockResolvedValue(undefined)
          }
        }
      ]
    }).compileComponents();
    const fixture = TestBed.createComponent(ShellComponent);
    fixture.detectChanges();

    const skipLink = fixture.nativeElement.querySelector('a.skip-link') as HTMLAnchorElement;
    const navigation = fixture.nativeElement.querySelector('nav[aria-label="Основная навигация"]') as HTMLElement;
    const main = fixture.nativeElement.querySelector('main') as HTMLElement;

    expect(skipLink.getAttribute('href')).toBe('#cp-main-content');
    expect(navigation).not.toBeNull();
    expect(main.id).toBe('cp-main-content');
    expect(main.tabIndex).toBe(-1);
    expect(fixture.nativeElement.querySelector('button[aria-label="Выйти из Control Panel"]')).not.toBeNull();
  });
});
