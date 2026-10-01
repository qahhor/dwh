export interface User {
  id: number;
  name: string;
  login: string;
  email: string;
  phone?: string;
  state: 'A' | 'P';
  managerId?: number;
  language: string;
  timezone: string;
  avatarFileId?: string;
  attributes: Record<string, unknown>;
  is2faEnabled: boolean;
  forcePasswordChange: boolean;
  roleIds?: number[];
  passwordChangedAt?: string;
  createdAt: string;
  modifiedAt: string;
  /** What a change of the record names in If-Match (plan item 3.6). */
  revision?: number;
}

export interface LoginResponse {
  step: 'success' | 'otp';
  otpToken?: string;
  user?: User;
}

export interface MeResponse {
  user: User;
  permissions: string[];
  permissionsVersion: number;
}

export interface UserSession {
  id: number;
  userId: number;
  ip: string;
  userAgent: string;
  deviceInfo: string;
  createdAt: string;
  lastSeenAt: string;
  closedAt?: string;
  current?: boolean;
}

export interface ApiToken {
  id: number;
  userId: number;
  name: string;
  tokenPrefix: string;
  expiresAt?: string;
  lastUsedAt?: string;
  createdAt: string;
  revokedAt?: string;
}

export interface CreatedTokenResponse {
  record: ApiToken;
  rawSecretToken: string;
}

export interface LoginAttemptRecord {
  id: number;
  login: string;
  ip: string;
  isSuccess: boolean;
  failureReason?: string;
  attemptAt: string;
}

export interface UserSecuritySummary {
  userId: number;
  login: string;
  is2faEnabled: boolean;
  forcePasswordChange: boolean;
  passwordChangedAt?: string;
  createdAt: string;
  authVersion: number;
  activeSessionsCount: number;
  activeSessions: UserSession[];
  recentLoginAttempts: LoginAttemptRecord[];
}
