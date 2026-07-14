export type SecurityRole = {
  name: string;
  label: string;
  description: string;
  permissions: string[];
};

export type GroupLite = {
  id: number;
  name: string;
};

export type SecurityUser = {
  id: number;
  username: string;
  displayName?: string | null;
  active: boolean;
  createdAt?: string | null;
  updatedAt?: string | null;
  roles: string[];
  groups: GroupLite[];
};

export type SecurityGroup = {
  id: number;
  name: string;
  description?: string | null;
  createdAt?: string | null;
  roles: string[];
};

export type SecuritySummary = {
  users: SecurityUser[];
  groups: SecurityGroup[];
  roles: SecurityRole[];
};

export type UserRequest = {
  username?: string;
  displayName?: string | null;
  password?: string | null;
  active?: boolean | null;
  roles?: string[];
  groupIds?: number[];
};

export type GroupRequest = {
  name?: string;
  description?: string | null;
  roles?: string[];
};
