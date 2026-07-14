'use client';

import { useQuery } from '@tanstack/react-query';

import { apiFetch } from '@/lib/api';
import { keys } from '@/lib/keys';
import type { DataSource, MaskingPolicy, MaskingRule, MaskingScript } from '@/lib/types';
import type { SyntheticValueList } from '@/features/synthetic/types';
import type { DiscoveryFinding } from './types';

export function useMaskingFunctions() {
  return useQuery({
    queryKey: keys.policies.functions,
    queryFn: () => apiFetch<string[]>('/api/policies/functions')
  });
}

export function useMaskingScripts() {
  return useQuery({
    queryKey: keys.policies.scripts,
    queryFn: () => apiFetch<MaskingScript[]>('/api/policies/scripts')
  });
}

export function useMaskingValueLists() {
  return useQuery({
    queryKey: keys.synthetic.valueLists,
    queryFn: () => apiFetch<SyntheticValueList[]>('/api/synthetic/value-lists')
  });
}

export function useMaskingLookupReferences() {
  return useQuery({
    queryKey: keys.policies.lookupReferences,
    queryFn: () => apiFetch<string[]>('/api/policies/lookup-references')
  });
}

export function usePolicies() {
  return useQuery({
    queryKey: keys.policies.all,
    queryFn: () => apiFetch<MaskingPolicy[]>('/api/policies')
  });
}

export function usePolicyRules(policyId: number | null) {
  return useQuery({
    queryKey: keys.policies.rules(policyId),
    enabled: !!policyId,
    queryFn: () => apiFetch<MaskingRule[]>(`/api/policies/${policyId}/rules`)
  });
}

export function useDataSources() {
  return useQuery({
    queryKey: keys.dataSources.all,
    queryFn: () => apiFetch<DataSource[]>('/api/datasources')
  });
}

export function useDiscoveryFindings(dataSourceId: number | null, schema: string) {
  return useQuery({
    queryKey: keys.policies.discoveryFindings(dataSourceId, schema),
    enabled: !!dataSourceId && !!schema.trim(),
    queryFn: () => apiFetch<DiscoveryFinding[]>(`/api/discovery/results/${dataSourceId}?schema=${encodeURIComponent(schema.trim())}`)
  });
}
