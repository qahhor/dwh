export interface Project {
  id: number;
  name: string;
  description?: string;
  state: 'A' | 'P';
  attributes: Record<string, any>;
  createdAt: string;
  modifiedAt: string;
  createdBy: number;
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
  pcode: string;
  name: string;
  colorHex?: string;
  isTerminal: boolean;
  orderNo: number;
}

export interface Task {
  id: number;
  projectId?: number;
  parentTaskId?: number;
  title: string;
  descriptionMarkdown?: string;
  statusId: number;
  priority: 'low' | 'normal' | 'high' | 'urgent';
  reporterId: number;
  attributes: Record<string, any>;
  beginTime?: string;
  endTime?: string;
  resolvedTime?: string;
  createdAt: string;
  modifiedAt: string;
  createdBy: number;
  modifiedBy: number;
}

export interface TaskMember {
  taskId: number;
  userId: number;
  involvementKind: 'A' | 'R' | 'E' | 'D' | 'V'; // Author, Responsible, Executor, Director, Viewer
  isDirect: boolean;
  viewedAt?: string;
  userName: string;
  userLogin: string;
  userEmail: string;
}

export interface TaskComment {
  id: number;
  taskId: number;
  userId: number;
  userName: string;
  userLogin: string;
  commentMarkdown: string;
  createdAt: string;
  modifiedAt: string;
}
