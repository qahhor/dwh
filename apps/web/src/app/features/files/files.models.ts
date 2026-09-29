export interface FileDetail {
  id: string;
  originalName: string;
  sizeBytes: number;
  mimeType: string;
  createdAt: string;
  createdBy?: number;
  creatorName?: string;
  creatorLogin?: string;
}

export interface StorageStats {
  companyQuotaBytes: number;
  companyUsedBytes: number;
  companyAvailableBytes: number;
  userQuotaBytes: number;
  userUsedBytes: number;
  userAvailableBytes: number;
  totalFilesCount: number;
  userFilesCount: number;
}
