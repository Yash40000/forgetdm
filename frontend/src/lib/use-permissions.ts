'use client';

/**
 * Client-side permission layer. Mirrors the backend AccessControlFilter contract:
 * every write/run/admin route requires a specific permission, and `admin.all` is a
 * wildcard that satisfies any permission (see io.forgetdm.security.AccessPrincipal).
 *
 * The backend is always authoritative — this only controls whether the UI *offers*
 * an action, so a read-only user is not shown controls that would 403. Until
 * `/api/auth/me` has resolved we default-deny (can() === false) so forbidden
 * controls never flash in before identity is known.
 */
import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';

import { apiFetch } from '@/lib/api';
import { keys } from '@/lib/keys';

export type AuthMe = {
  authenticated?: boolean;
  user?: {
    userId?: number;
    username?: string;
    displayName?: string;
    roles?: string[];
    permissions?: string[];
    /** Group membership — added with the V61 tenancy work (closes DEF-0002). */
    groups?: { id: number; name: string }[];
  };
};

export const ADMIN_ALL = 'admin.all';

export type PermissionApi = {
  /** True when the signed-in user holds the permission (or admin.all). */
  can: (permission: string) => boolean;
  /** True when at least one of the permissions is held. */
  canAny: (...permissions: string[]) => boolean;
  /** Raw effective permission list. */
  permissions: string[];
  roles: string[];
  isAdmin: boolean;
  /** False while /api/auth/me is still loading. */
  ready: boolean;
};

export function usePermissions(): PermissionApi {
  const meQuery = useQuery({
    queryKey: keys.auth.me,
    queryFn: () => apiFetch<AuthMe>('/api/auth/me'),
    retry: false,
    staleTime: 60_000
  });

  return useMemo(() => {
    const user = meQuery.data?.authenticated ? meQuery.data.user : undefined;
    const permissions = user?.permissions ?? [];
    const roles = user?.roles ?? [];
    const ready = meQuery.isSuccess;
    const isAdmin = permissions.includes(ADMIN_ALL) || roles.includes('ADMIN');
    const can = (permission: string) =>
      ready && (permissions.includes(ADMIN_ALL) || permissions.includes(permission));
    const canAny = (...perms: string[]) => perms.some((perm) => can(perm));
    return { can, canAny, permissions, roles, isAdmin, ready };
  }, [meQuery.data, meQuery.isSuccess]);
}
