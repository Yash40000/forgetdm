'use client';

/**
 * Declarative permission gate. Renders `children` only when the signed-in user
 * holds the required permission (admin.all is a wildcard). Optionally renders a
 * `fallback` (e.g. a disabled/explanatory element) instead.
 *
 * Usage:
 *   <Can permission="policy.manage"><Button>New policy</Button></Can>
 *   <Can permission="policy.manage" any={["policy.manage","admin.all"]}>…</Can>
 *
 * The backend remains authoritative; this only controls whether the control is offered.
 */
import type { ReactNode } from 'react';

import { usePermissions } from '@/lib/use-permissions';

export function Can({
  permission,
  any,
  fallback = null,
  children
}: {
  permission?: string;
  any?: string[];
  fallback?: ReactNode;
  children: ReactNode;
}) {
  const { can, canAny } = usePermissions();
  const allowed = any && any.length ? canAny(...any) : permission ? can(permission) : false;
  return <>{allowed ? children : fallback}</>;
}
