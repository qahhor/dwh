import { Project } from '@core/models/task.models';

/** A project as the registry list answers it (ms.projects): the counts are over the viewer's tasks, empty without the task right. */
export interface ProjectListItem extends Project {
  progress?: number | null;
}

export type ProjectViewState = 'list' | 'cards';
export type ProjectStateFilter = 'all' | 'A' | 'P';

export interface ProjectMember {
  projectId: number;
  userId: number;
  userName: string;
  userEmail: string;
  accessKind: 'MANAGER' | 'MEMBER' | 'OBSERVER' | string;
}

export interface AddProjectMemberDto {
  userId: number;
  accessKind: string;
}

export interface ProjectCreateForm {
  name: string;
  description: string;
  attributes?: Record<string, unknown>;
}

export interface ProjectEditForm {
  name: string;
  description: string;
  state: 'A' | 'P';
  attributes?: Record<string, unknown>;
}

export interface ProjectAttributeItem {
  key: string;
  value: string;
}
