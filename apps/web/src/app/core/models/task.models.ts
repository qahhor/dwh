export interface Project {
  id: number;
  name: string;
  description?: string;
  state: 'A' | 'P';
  attributes?: Record<string, unknown>;
  createdAt: string;
  modifiedAt?: string;
  createdBy?: number;
  totalTasks?: number;
  activeTasks?: number;
  doneTasks?: number;
  /** What a change of the record names in If-Match (plan item 3.6). */
  revision?: number;
}

export interface ProjectTaskStats {
  projectId: number;
  totalTasks: number;
  activeTasks: number;
  doneTasks: number;
}

export interface ProjectMember {
  projectId: number;
  userId: number;
  accessKind: 'admin' | 'member' | 'viewer';
  userName: string;
  userLogin: string;
  createdAt: string;
}

export interface TaskStatus {
  id: number;
  pcode?: string | null;
  name: string;
  color?: string;
  colorHex?: string;
  isTerminal: boolean;
  orderNo: number;
  /** What a change of the record names in If-Match (plan item 3.6). */
  revision?: number;
}

export interface TaskType {
  id: number;
  code: string;
  name: string;
  icon: string;
  color: string;
  orderNo: number;
  isSystem: boolean;
  /** What a change of the record names in If-Match (plan item 3.6). */
  revision?: number;
}

export interface Task {
  id: number;
  projectId?: number | null;
  parentTaskId?: number | null;
  title: string;
  descriptionMarkdown?: string;
  statusId: number;
  priority: 'low' | 'medium' | 'high' | 'critical' | string;
  reporterId?: number;
  attributes: Record<string, unknown>;
  beginTime?: string | null;
  endTime?: string | null;
  resolvedTime?: string | null;
  createdAt: string;
  modifiedAt?: string;
  createdBy?: number;
  modifiedBy?: number;
  /** What a change of the task names: `expectedRevision` or If-Match (plan item 3.6). */
  revision?: number;
}

export interface TaskMember {
  taskId: number;
  userId: number;
  involveKind?: 'A' | 'R' | 'E' | 'D' | 'V' | 'O' | 'P' | string;
  involvementKind?: 'A' | 'R' | 'E' | 'D' | 'V' | 'O' | 'P' | string;
  isViewed?: boolean;
  isDirect?: boolean;
  viewedAt?: string;
  userName: string;
  userLogin: string;
  userEmail?: string;
}

export interface TaskComment {
  id: number;
  taskId: number;
  userId: number;
  userName: string | null;
  userLogin: string | null;
  commentMarkdown?: string;
  textMarkdown?: string;
  createdAt: string;
  modifiedAt?: string;
}

export interface TaskFile {
  fileId: string;
  fileName: string;
  sizeBytes: number;
  mimeType: string;
  createdAt: string;
}

export interface TaskDetailResponse {
  task: Task;
  members: TaskMember[];
  subtasks?: Task[];
  ancestors?: Task[];
  files?: TaskFile[];
}
