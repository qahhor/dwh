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

/** A task status: a record of the reference entity `ms.task_statuses` on the general runtime (ADR-0032 8). */
export interface TaskStatus {
  id: number;
  /** What a task's status keeps; given once and kept. */
  code: string;
  name: string;
  color?: string;
  /** A terminal status closes the task. */
  terminal: boolean;
  sortOrder: number;
  /** One of the statuses the product ships with: never archived or deleted. */
  system?: boolean;
  /** What a change of the record names in If-Match (ADR-0024). */
  revision?: number;
}

/** A task type: a record of the reference entity `ms.task_types` on the general runtime (ADR-0032 8). */
export interface TaskType {
  id: number;
  code: string;
  name: string;
  icon: string;
  color: string;
  sortOrder: number;
  /** One of the types the product ships with: never archived or deleted. */
  system?: boolean;
  /** What a change of the record names in If-Match (ADR-0024). */
  revision?: number;
}

export interface Task {
  id: number;
  projectId?: number | null;
  /** The project's name, answered with the task, so the screen needs no list of projects (plan 10/10, item 3.5). */
  projectName?: string | null;
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
