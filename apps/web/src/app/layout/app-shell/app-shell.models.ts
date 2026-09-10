export interface NavItem {
  id: string;
  route?: string;
  labelKey?: string;
  label?: string;
  titleKey?: string;
  icon: string;
  permission: () => boolean;
  exact?: boolean;
  badge?: () => number;
  children?: NavItem[];
  external?: boolean;
  targetUrl?: string;
  openInIframe?: boolean;
}

export interface NavSection {
  id: string;
  titleKey: string;
  items: NavItem[];
}
