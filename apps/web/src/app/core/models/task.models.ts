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

/**
 * A task: a record of `ms.tasks` on the general runtime (ADR-0032 8). Its status and type are codes of the reference
 * lists `ms.task_statuses` and `ms.task_types`; the status changes only by the record action `set_status`.
 */
export interface Task {
  id: number;
  title: string;
  descriptionMarkdown?: string | null;
  /** The code of the task's type (`ms.task_types`). */
  typeCode: string;
  /** The code of the task's status (`ms.task_statuses`). */
  statusCode: string;
  priority: 'low' | 'medium' | 'high' | 'critical' | string;
  projectId?: number | null;
  /** The project's name, answered with each list row, so the screen needs no list of projects (plan 10/10, item 3.5). */
  projectName?: string | null;
  parentTaskId?: number | null;
  responsibleId?: number | null;
  executorIds?: number[];
  observerIds?: number[];
  reporterId?: number;
  beginTime?: string | null;
  endTime?: string | null;
  resolvedTime?: string | null;
  createdAt?: string;
  modifiedAt?: string;
  /** Custom field values by field code. */
  attributes: Record<string, unknown>;
  /** What a change of the task names in If-Match (ADR-0024). */
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
