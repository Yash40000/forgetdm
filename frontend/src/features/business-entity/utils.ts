import type { BeReservation, BeSnapshot, BusinessEntityDetail, CapsuleInstance } from './types';

/**
 * The entity lifecycle, Linear-style: a single rail of stages instead of ten boxed tabs.
 * Stages shipped in this UI carry a tab id; the rest still live in the classic console.
 */
export type BeStage = {
  id: string;
  label: string;
  goal: string;
  tab?: string;
  classic?: boolean;
};

export const BE_STAGES: BeStage[] = [
  { id: 'model', label: 'Model', goal: 'Define the tables and keys that make up the entity.', tab: 'model' },
  { id: 'identity', label: 'Identity', goal: 'Map one canonical key to every system.', tab: 'identity' },
  { id: 'freshness', label: 'Freshness', goal: 'Watermark SLAs per source slice.', tab: 'freshness' },
  { id: 'time', label: 'Reserve', goal: 'Snapshot points in time and reserve entities per team.', tab: 'time' },
  { id: 'microdb', label: 'Micro-DB', goal: 'Keep each entity as a governed, encrypted capsule.', tab: 'microdb' },
  { id: 'deliver', label: 'Deliver', goal: 'Issue recreation, flows, execution plans.', tab: 'deliver' },
  { id: 'govern', label: 'Govern', goal: 'Maker-checker approvals and evidence.', tab: 'govern' }
];

export type BeStageState = { done: boolean; hint: string };

export function stageStates(
  detail: BusinessEntityDetail | undefined,
  snapshots: BeSnapshot[],
  reservations: BeReservation[],
  capsules: CapsuleInstance[],
  identities: Array<Record<string, unknown>> = [],
  syncPolicies: Array<Record<string, unknown>> = [],
  enterprise: Record<string, unknown> = {}
): Record<string, BeStageState> {
  const entity = detail?.entity;
  const members = detail?.members || [];
  const activeReservations = reservations.filter((r) => r.status === 'ACTIVE').length;
  const listOf = (key: string) => (Array.isArray(enterprise[key]) ? (enterprise[key] as unknown[]) : []);
  const deliverCount = listOf('issuePackages').length + listOf('lookalikeProfiles').length + listOf('executionPlans').length;
  const governanceRequests = listOf('governanceRequests') as Array<Record<string, unknown>>;
  const pendingGovernance = governanceRequests.filter((request) => request.status === 'PENDING').length;
  return {
    model: {
      done: Boolean(entity?.id && members.length && entity?.businessKeyColumns),
      hint: members.length ? `${members.length} table${members.length === 1 ? '' : 's'}` : 'start here'
    },
    identity: {
      done: identities.length > 0,
      hint: identities.length ? `${identities.length} crosswalk${identities.length === 1 ? '' : 's'}` : 'optional'
    },
    freshness: {
      done: syncPolicies.length > 0,
      hint: syncPolicies.length ? `${syncPolicies.length} polic${syncPolicies.length === 1 ? 'y' : 'ies'}` : 'recommended'
    },
    time: {
      done: snapshots.length > 0 || activeReservations > 0,
      hint: `${snapshots.length} snapshot${snapshots.length === 1 ? '' : 's'} · ${activeReservations} reserved`
    },
    microdb: {
      done: capsules.length > 0,
      hint: capsules.length ? `${capsules.length} capsule${capsules.length === 1 ? '' : 's'}` : 'reusable entity store'
    },
    deliver: {
      done: deliverCount > 0,
      hint: deliverCount ? `${deliverCount} artifact${deliverCount === 1 ? '' : 's'}` : 'plans, flows, packages'
    },
    govern: {
      done: governanceRequests.length > 0 && !pendingGovernance,
      hint: pendingGovernance ? `${pendingGovernance} pending approval` : 'maker-checker'
    }
  };
}

/* Loose-map helpers for the enterprise endpoints (backend returns Map<String,Object>). */
export function str(value: unknown, fallback = ''): string {
  return value === null || value === undefined ? fallback : String(value);
}

export function num(value: unknown): number | null {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

export function listOfMaps(source: Record<string, unknown>, key: string): Array<Record<string, unknown>> {
  return Array.isArray(source[key]) ? (source[key] as Array<Record<string, unknown>>) : [];
}

export function formatDate(value?: string | null) {
  if (!value) return '-';
  try {
    return new Date(value).toLocaleString();
  } catch {
    return String(value);
  }
}

/** Linear-style status dot color for entities, reservations, capsules. */
export function statusDot(status?: string | null) {
  const clean = String(status || '').toUpperCase();
  if (['ACTIVE', 'READY', 'COMPLETED', 'CURRENT'].includes(clean)) return '#16a34a';
  if (['DRAFT', 'PENDING'].includes(clean)) return '#94a3b8';
  if (['RETIRED', 'RELEASED', 'EXPIRED', 'SUPERSEDED'].includes(clean)) return '#cbd5e1';
  if (['FAILED', 'REJECTED'].includes(clean)) return '#dc2626';
  if (['STALE', 'WARN', 'AWAITING_APPROVAL'].includes(clean)) return '#d97706';
  return '#2563eb';
}

export function parseBusinessKeyInput(raw: string): Record<string, string> | null {
  const out: Record<string, string> = {};
  for (const part of raw.split(',')) {
    if (!part.trim()) continue;
    const eq = part.indexOf('=');
    if (eq <= 0) return null;
    out[part.slice(0, eq).trim()] = part.slice(eq + 1).trim();
  }
  return Object.keys(out).length ? out : null;
}

export function numberOrNull(value: string | number | null | undefined) {
  if (value === null || value === undefined || value === '') return null;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

export const technicalInputProps = {
  autoCapitalize: 'none',
  autoCorrect: 'off',
  spellCheck: false
} as const;
