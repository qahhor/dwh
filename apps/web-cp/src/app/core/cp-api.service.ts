import { Injectable, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';

export interface CpUser { login: string; name: string; roles: string[]; }

export interface FleetItem {
  instanceId: number; clientCode: string; clientName: string;
  resourceProfile: string; environment: string; url: string;
  appVersion: string | null; schemaVersion: string | null;
  lastHeartbeatAt: string | null; health: 'UP' | 'DOWN' | 'NEVER';
  licenseStatus?: string;
}

export interface FleetResponse {
  items: FleetItem[]; total: number; problems: number; heartbeatTimeoutMinutes: number;
}

export interface Client { id: number; code: string; name: string; resourceProfile: string; createdAt: string; }

export interface InstanceRegistrationRequest {
  clientCode: string;
  environment: 'production' | 'staging' | 'dev';
  url: string;
  deploymentMode: 'MANAGED_CLOUD' | 'CUSTOMER_HOSTED';
  jurisdiction: string;
  cloudProvider: string;
  storageProvider: string;
  edgeProvider: string | null;
  supportTier: 'MANAGED_995' | 'CUSTOMER_HOSTED_SUPPORT';
}

export interface InstanceEnrollment {
  instanceId: number;
  enrollmentToken: string;
  expiresAt: string;
}

export interface BackupCheck {
  id: number; clientCode: string; success: boolean;
  durationSec: number; details: string | null; verifiedAt: string;
}

export interface InstanceBackupReport {
  backupId: string;
  instanceId: number;
  clientCode: string;
  artifactStatus: 'UPLOADED' | 'VERIFIED' | 'FAILED';
  checksumSha256: string | null;
  durationSec: number;
  reasonCode: string | null;
  completedAt: string;
  receivedAt: string;
  verifiedAt: string | null;
}

export interface AnnouncementContent { language: string; title: string; body: string; }

export interface Announcement {
  id: number; bannerType: string; state: string; publishedAt: string | null;
  createdAt: string; targets: string;
  contents: AnnouncementContent[];
}

@Injectable({ providedIn: 'root' })
export class CpApiService {
  private http = inject(HttpClient);

  /** Текущий сотрудник; null — не аутентифицирован. */
  readonly user = signal<CpUser | null>(null);

  async login(login: string, password: string): Promise<CpUser> {
    const user = await firstValueFrom(
      this.http.post<CpUser>('/api/v1/auth/login', { login, password }, { withCredentials: true }));
    this.user.set(user);
    return user;
  }

  async logout(): Promise<void> {
    await firstValueFrom(this.http.post('/api/v1/auth/logout', {}, { withCredentials: true }));
    this.user.set(null);
  }

  /** Восстановление сессии при перезагрузке страницы. */
  async restoreSession(): Promise<CpUser | null> {
    try {
      const user = await firstValueFrom(
        this.http.get<CpUser>('/api/v1/auth/me', { withCredentials: true }));
      this.user.set(user);
      return user;
    } catch {
      this.user.set(null);
      return null;
    }
  }

  fleet() { return firstValueFrom(this.http.get<FleetResponse>('/api/v1/fleet', { withCredentials: true })); }

  clients() { return firstValueFrom(this.http.get<Client[]>('/api/v1/clients', { withCredentials: true })); }

  createClient(code: string, name: string, resourceProfile: string) {
    return firstValueFrom(this.http.post<{ id: number }>('/api/v1/clients',
      { code, name, resourceProfile }, { withCredentials: true }));
  }

  async registerInstance(request: InstanceRegistrationRequest): Promise<InstanceEnrollment> {
    return firstValueFrom(
      this.http.post<InstanceEnrollment>(
        '/api/v1/instances', request, { withCredentials: true }));
  }

  async updateInstanceStatus(instanceId: number, status: string): Promise<void> {
    await firstValueFrom(
      this.http.put<{ instanceId: number; status: string }>(
        `/api/v1/instances/${instanceId}/status`, { status }, { withCredentials: true }));
  }

  backupChecks() {
    return firstValueFrom(this.http.get<BackupCheck[]>('/api/v1/backup-checks', { withCredentials: true }));
  }

  backupReports() {
    return firstValueFrom(this.http.get<InstanceBackupReport[]>(
      '/api/v1/backup-reports', { withCredentials: true }));
  }

  announcements() {
    return firstValueFrom(this.http.get<Announcement[]>('/api/v1/announcements', { withCredentials: true }));
  }

  createAnnouncement(bannerType: string, title: string, body: string) {
    return firstValueFrom(this.http.post<{ id: number }>('/api/v1/announcements', {
      bannerType, contents: [{ language: 'ru', title, body }], targetClientIds: []
    }, { withCredentials: true }));
  }

  publishAnnouncement(id: number) {
    return firstValueFrom(this.http.post(`/api/v1/announcements/${id}/publish`, {}, { withCredentials: true }));
  }

  archiveAnnouncement(id: number) {
    return firstValueFrom(this.http.post(`/api/v1/announcements/${id}/archive`, {}, { withCredentials: true }));
  }

  modules() {
    return firstValueFrom(this.http.get<any[]>('/api/v1/moderation/modules', { withCredentials: true }));
  }

  approveModule(id: number, notes: string) {
    return firstValueFrom(this.http.post(`/api/v1/moderation/modules/${id}/approve`, { notes }, { withCredentials: true }));
  }

  rejectModule(id: number, notes: string) {
    return firstValueFrom(this.http.post(`/api/v1/moderation/modules/${id}/reject`, { notes }, { withCredentials: true }));
  }
}
