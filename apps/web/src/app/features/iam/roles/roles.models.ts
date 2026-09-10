export interface FormActionItem {
  action: string;
  actionName: string;
}

export interface GroupedForm {
  module: string;
  formCode: string;
  formName: string;
  actions: FormActionItem[];
}

export interface ModuleGroup {
  moduleCode: string;
  moduleName: string;
  forms: GroupedForm[];
  isExpanded: boolean;
}
