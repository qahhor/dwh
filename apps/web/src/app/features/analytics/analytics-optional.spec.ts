import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ModuleService } from '@core/services/module.service';
import { PermissionService } from '@core/services/permission.service';
import { EntityReportsApi } from '@shared/entity/report/entity-reports';
import { AnalyticsComponent } from './analytics.component';

describe('analytics without the task application', () => {
  it('keeps entity widgets without reading task data or offering task actions', async () => {
    const active = signal(false);
    const widgets = vi.fn(() => of([]));
    TestBed.configureTestingModule({
      imports: [AnalyticsComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: ModuleService, useValue: { isLoaded: () => true, isModuleActive: () => active() } },
        { provide: PermissionService, useValue: { canView: () => true, hasPermission: () => true } },
        { provide: EntityReportsApi, useValue: { widgets } },
      ],
    });
    const fixture = TestBed.createComponent(AnalyticsComponent);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectNone((request) => request.url.startsWith('/api/v1/analytics'));
    expect(widgets).toHaveBeenCalled();
    expect(fixture.nativeElement.querySelector('app-analytics-widgets')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('app-analytics-metrics-tiles')).toBeNull();
    expect(fixture.nativeElement.querySelector('.range-picker')).toBeNull();
    http.verify();
  });
});
