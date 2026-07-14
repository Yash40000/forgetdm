'use client';

import { useQuery } from '@tanstack/react-query';

import { apiFetch } from '@/lib/api';
import { keys } from '@/lib/keys';
import type { AuditFacets, AuditFilters, AuditSearchResult, AuditStats, AuditVerify } from './types';

export function buildAuditQuery(filters: AuditFilters): string {
  const params = new URLSearchParams();
  if (filters.q) params.set('q', filters.q);
  if (filters.category) params.set('category', filters.category);
  if (filters.action) params.set('action', filters.action);
  if (filters.actor) params.set('actor', filters.actor);
  if (filters.outcome) params.set('outcome', filters.outcome);
  if (filters.from) params.set('from', filters.from);
  params.set('page', String(filters.page ?? 0));
  params.set('size', String(filters.size ?? 50));
  return params.toString();
}

export function useAuditSearch(filters: AuditFilters) {
  const query = buildAuditQuery(filters);
  return useQuery({
    queryKey: keys.audit.search(filters as Record<string, unknown>),
    queryFn: () => apiFetch<AuditSearchResult>(`/api/audit?${query}`),
    placeholderData: (previous) => previous
  });
}

export function useAuditFacets() {
  return useQuery({ queryKey: keys.audit.facets, queryFn: () => apiFetch<AuditFacets>('/api/audit/facets') });
}

export function useAuditStats() {
  return useQuery({ queryKey: keys.audit.stats, queryFn: () => apiFetch<AuditStats>('/api/audit/stats') });
}

export function useAuditVerify() {
  return useQuery({ queryKey: keys.audit.verify, queryFn: () => apiFetch<AuditVerify>('/api/audit/verify') });
}
