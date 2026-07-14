import type { SecurityGroup, SecurityRole } from './types';

export const ADMIN_PERMISSION = 'admin.all';

export function roleMap(roles: SecurityRole[]): Record<string, SecurityRole> {
  return Object.fromEntries(roles.map((role) => [role.name, role]));
}

/** Union of permissions granted by the given direct roles plus the roles of the selected groups. */
export function effectivePermissions(
  roleNames: string[],
  groupIds: number[],
  groups: SecurityGroup[],
  roles: SecurityRole[]
): string[] {
  const byName = roleMap(roles);
  const groupById = new Map(groups.map((group) => [group.id, group]));
  const permissions = new Set<string>();
  const addRole = (name: string) => {
    const role = byName[name];
    if (role) role.permissions.forEach((permission) => permissions.add(permission));
  };
  roleNames.forEach(addRole);
  groupIds.forEach((id) => (groupById.get(id)?.roles || []).forEach(addRole));
  return Array.from(permissions).sort();
}

export function isAdminPermissionSet(permissions: string[]) {
  return permissions.includes(ADMIN_PERMISSION);
}

/** Group "domain.action" permissions by their domain prefix for legible display. */
export function permissionsByDomain(permissions: string[]): Array<{ domain: string; actions: string[] }> {
  const grouped = new Map<string, string[]>();
  for (const permission of permissions) {
    const dot = permission.indexOf('.');
    const domain = dot > 0 ? permission.slice(0, dot) : permission;
    const action = dot > 0 ? permission.slice(dot + 1) : permission;
    if (!grouped.has(domain)) grouped.set(domain, []);
    grouped.get(domain)!.push(action);
  }
  return Array.from(grouped.entries())
    .map(([domain, actions]) => ({ domain, actions: actions.sort() }))
    .sort((a, b) => a.domain.localeCompare(b.domain));
}
