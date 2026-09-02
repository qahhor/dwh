import { TestBed } from '@angular/core/testing';
import { Subject, of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/services/api.service';
import { SystemComponent, SystemInfo } from './system.component';

describe('SystemComponent', () => {
  const systemInfo: SystemInfo = {
    appVersion: '1.4.0',
    schemaVersion: '12',
    organization: {
      code: 'acme',
      name: 'Acme Distribution',
      resourceProfile: 'M'
    },
    storageProvider: 's3',
    components: {
      database: { status: 'UP' },
      storage: { status: 'DEGRADED' },
      typesense: { status: 'DISABLED' }
    },
    backup: {
      status: 'SUCCESS',
      completedAt: '2026-09-02T09:00:00Z',
      failureCode: null
    }
  };

  async function createFixture(getResult = of(systemInfo)) {
    const api = { get: vi.fn(() => getResult) };
    await TestBed.configureTestingModule({
      imports: [SystemComponent],
      providers: [{ provide: ApiService, useValue: api }]
    }).compileComponents();
    const fixture = TestBed.createComponent(SystemComponent);
    fixture.detectChanges();
    return { fixture, api };
  }

  it('renders a semantic, non-secret operational summary', async () => {
    const responseWithPrivateFields = {
      ...systemInfo,
      archivePath: 's3://private-backups/backup.tar',
      secretKey: 'must-not-render'
    };
    const { fixture, api } = await createFixture(of(responseWithPrivateFields));

    expect(api.get).toHaveBeenCalledWith('/system/info');
    expect(fixture.nativeElement.querySelector('section[aria-labelledby="system-title"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('dl[aria-label="Состояние компонентов"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('[data-status="UP"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('[data-status="DEGRADED"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('[data-status="DISABLED"]')).not.toBeNull();
    expect(fixture.nativeElement.textContent).not.toContain('s3://private-backups');
    expect(fixture.nativeElement.textContent).not.toContain('must-not-render');
  });

  it.each([
    ['NEVER', 'Резервная копия ещё не создавалась'],
    ['FAILED', 'Последняя резервная копия завершилась ошибкой'],
    ['SUCCESS', 'Последняя резервная копия создана']
  ] as const)('explains backup status %s', async (status, expectedText) => {
    const { fixture } = await createFixture(of({
      ...systemInfo,
      backup: {
        status,
        completedAt: status === 'NEVER' ? null : systemInfo.backup.completedAt,
        failureCode: status === 'FAILED' ? 'UPLOAD_FAILED' : null
      }
    }));

    expect(fixture.nativeElement.querySelector('[data-testid="backup-status"]')?.textContent).toContain(expectedText);
    expect(fixture.nativeElement.textContent).toContain('резервное копирование, восстановление и обновление выполняются через CLI');
    expect(fixture.nativeElement.querySelector('[data-action="backup"]')).toBeNull();
    expect(fixture.nativeElement.querySelector('[data-action="restore"]')).toBeNull();
    expect(fixture.nativeElement.querySelector('[data-action="update"]')).toBeNull();
  });

  it('shows loading and recoverable error states', async () => {
    const pending = new Subject<SystemInfo>();
    const loading = await createFixture(pending);
    expect(loading.fixture.nativeElement.querySelector('[aria-busy="true"]')).not.toBeNull();

    TestBed.resetTestingModule();
    const failed = await createFixture(throwError(() => ({ status: 503 })));
    expect(failed.fixture.nativeElement.querySelector('[role="alert"]')?.textContent).toContain('Не удалось загрузить состояние системы');
    expect(failed.fixture.nativeElement.querySelector('button[aria-label="Повторить загрузку состояния системы"]')).not.toBeNull();
  });
});
